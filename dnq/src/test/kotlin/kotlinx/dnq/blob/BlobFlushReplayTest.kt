/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.blob

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.query.first
import mu.KLogging
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PROBE (JT-95771): does a flush replay destroy the content of a blob written in the same
 * transaction?
 *
 * `TransientEntitiesUpdaterImpl.setBlob` (:57-65) queues the caller's [InputStream] BY REFERENCE
 * inside an `addChangeAndRun { ... transientEntity.entity.setBlob(blobName, stream) ... }` closure.
 * `YTDBVertexEntity.setBlob` consumes that stream eagerly (`readAllBytes()`).
 *
 * When the flush loses an MVCC race, `TransientSessionImpl.flush` (:299-317) catches
 * `NeedRetryException` and calls `replayChanges()` -> `changes.forEach { it() }`
 * (`TransientEntitiesUpdaterImpl` :530-540), re-running that closure with the SAME, already
 * exhausted stream. A second `readAllBytes()` on a drained stream returns 0 bytes, so the replay
 * would persist an EMPTY blob and silently destroy the payload.
 *
 * That is the suspected cause of the intermittent YouTrack failure
 * `AverageAgeReportBundleModificationTest > merge elements of bundle used in report settings`,
 * where `ReportsDataMapper.deserializeJson` gets `EOFException` from `GZIPInputStream` because
 * `report.data` came back as a non-null but 0-byte stream.
 *
 * Scaffolding is modelled on `kotlinx.dnq.linkConstraints.NeedRetryReplayTest`.
 */
class BlobFlushReplayTest : DBTest() {

    companion object : KLogging() {
        const val TRANSIENT_STORE_LOGGER = "com.jetbrains.teamsys.dnq.database.TransientSessionImpl"
        const val REPLAY_MARKER = "Replaying changes"

        val PAYLOAD: ByteArray = "the quick brown fox jumps over the lazy dog".repeat(4).toByteArray()
    }

    class RetryCounter(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<RetryCounter>()

        var value by xdRequiredIntProp()
    }

    class BlobHolder(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<BlobHolder>()

        var name by xdRequiredStringProp()
        var payload by xdBlobProp()
        var text by xdBlobStringProp()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(RetryCounter, BlobHolder)
    }

    /** BASELINE — no race, no replay. Must pass: proves the harness writes/reads the blob correctly. */
    @Test
    fun `baseline — blob written without a replay survives`() {
        val holder = transactional { BlobHolder.new { name = "baseline" } }
        transactional { holder.payload = PAYLOAD.inputStream() }

        transactional {
            assertThat(holder.payload?.readBytes()).isEqualTo(PAYLOAD)
        }
    }

    /**
     * THE PROBE — the losing transaction writes a blob, then loses the MVCC race and replays.
     *
     * If the replay re-runs the queued `setBlob` closure with the exhausted stream, the committed
     * blob ends up empty (0 bytes) instead of [PAYLOAD].
     */
    @Test
    fun `blob written in a transaction that replays must not be truncated`() {
        val holder = transactional { BlobHolder.new { name = "racer" } }

        val outcome = raceWithReplay {
            holder.payload = PAYLOAD.inputStream()
        }

        outcome.report("blob / setBlob")
        outcome.failIfLoserFailed()
        outcome.assertReplayHappened()

        transactional {
            val bytes = holder.payload?.readBytes()
            logger.info { "committed blob size = ${bytes?.size} (expected ${PAYLOAD.size})" }
            assertThat(bytes).isNotNull()
            assertThat(bytes!!.size).isEqualTo(PAYLOAD.size)
            assertThat(bytes).isEqualTo(PAYLOAD)
        }
    }

    /** Same shape for `xdBlobStringProp`, which routes through `setBlobString` (a String, not a stream). */
    @Test
    fun `blob string written in a transaction that replays must not be truncated`() {
        val holder = transactional { BlobHolder.new { name = "racer-string" } }
        val text = "born in 1900 ".repeat(8)

        val outcome = raceWithReplay {
            holder.text = text
        }

        outcome.report("blob / setBlobString")
        outcome.failIfLoserFailed()
        outcome.assertReplayHappened()

        transactional {
            logger.info { "committed blob string = '${holder.text}'" }
            assertThat(holder.text).isEqualTo(text)
        }
    }

    // ---------------------------------------------------------------------------------------
    // scaffolding (trimmed copy of NeedRetryReplayTest's)
    // ---------------------------------------------------------------------------------------

    private fun raceWithReplay(loserWork: () -> Unit): RaceOutcome {
        transactional { RetryCounter.new { value = 0 } }
        val counter = transactional { RetryCounter.all().first() }

        val start = CyclicBarrier(2)
        val loserWorkDone = CountDownLatch(1)
        val winnerCommitted = CountDownLatch(1)
        var loserError: Throwable? = null
        var winnerError: Throwable? = null

        val capture = DebugLogCapture(TRANSIENT_STORE_LOGGER)
        try {
            val captureEnabled = capture.enabled
            val pool = Executors.newFixedThreadPool(2)
            try {
                val winner = pool.submit {
                    try {
                        transactional {
                            start.await(120, TimeUnit.SECONDS)
                            counter.value += 1
                            if (!loserWorkDone.await(30, TimeUnit.SECONDS)) {
                                throw AssertionError("loserWorkDone latch timed out after 30s")
                            }
                        }
                    } catch (t: Throwable) {
                        winnerError = t
                    } finally {
                        winnerCommitted.countDown()
                    }
                }

                val loser = pool.submit {
                    try {
                        transactional {
                            start.await(120, TimeUnit.SECONDS)
                            counter.value += 1
                            try {
                                loserWork()
                            } finally {
                                loserWorkDone.countDown()
                            }
                            if (!winnerCommitted.await(30, TimeUnit.SECONDS)) {
                                throw AssertionError("winnerCommitted latch timed out after 30s")
                            }
                        }
                    } catch (t: Throwable) {
                        loserError = t
                    } finally {
                        loserWorkDone.countDown()
                    }
                }

                listOf(winner, loser).forEach { it.get(120, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            return RaceOutcome(loserError, winnerError, capture.lines, captureEnabled)
        } finally {
            capture.close()
        }
    }

    private inner class RaceOutcome(
        val loserError: Throwable?,
        val winnerError: Throwable?,
        val lines: List<String>,
        val captureEnabled: Boolean
    ) {
        val replayLines: List<String> get() = lines.filter { REPLAY_MARKER in it }

        fun report(shape: String) {
            logger.info {
                "[$shape] capture enabled = $captureEnabled, lines: ${lines.size}, replays: ${replayLines.size}"
            }
            replayLines.forEach { logger.info { "[$shape] REPLAY EVIDENCE: $it" } }
            assertThat(winnerError).isNull()
        }

        fun assertReplayHappened() {
            if (captureEnabled) {
                assertThat(replayLines).isNotEmpty()
            } else {
                throw AssertionError("Log capture failed to enable — cannot verify a replay happened.")
            }
        }

        fun failIfLoserFailed() {
            loserError?.let { throw AssertionError("losing transaction failed: ${it::class.java.name}: ${it.message}", it) }
        }
    }

    private class DebugLogCapture(loggerName: String) : AutoCloseable {
        private val messages = Collections.synchronizedList(ArrayList<String>())
        private val originalErr: PrintStream = System.err
        private val restores = ArrayList<() -> Unit>()

        var enabled = false
            private set

        init {
            val logger = LoggerFactory.getLogger(loggerName)
            val field = generateSequence(logger.javaClass as Class<*>) { it.superclass }
                .mapNotNull { klass -> runCatching { klass.getDeclaredField("currentLogLevel") }.getOrNull() }
                .firstOrNull()
            if (field != null) {
                field.isAccessible = true
                val oldLevel = field.getInt(logger)
                field.setInt(logger, LOG_LEVEL_DEBUG)
                restores += { field.setInt(logger, oldLevel) }
                System.setErr(PrintStream(TeeStream(originalErr, messages), true))
                restores += { System.setErr(originalErr) }
                enabled = true
            }
        }

        val lines: List<String> get() = ArrayList(messages)

        override fun close() {
            restores.asReversed().forEach { runCatching { it() } }
            restores.clear()
            enabled = false
        }

        private class TeeStream(
            private val target: PrintStream,
            private val sink: MutableList<String>
        ) : OutputStream() {
            private val line = StringBuilder()

            @Synchronized
            override fun write(b: Int) {
                target.write(b)
                if (b == '\n'.code) {
                    sink.add(line.toString())
                    line.setLength(0)
                } else {
                    line.append(b.toChar())
                }
            }

            @Synchronized
            override fun flush() = target.flush()
        }

        companion object {
            private const val LOG_LEVEL_DEBUG = 10
        }
    }
}
