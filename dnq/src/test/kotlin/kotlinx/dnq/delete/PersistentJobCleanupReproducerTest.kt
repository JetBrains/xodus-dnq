/*
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
package kotlinx.dnq.delete

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.xdChildren0_N
import kotlinx.dnq.xdNullableLongProp
import kotlinx.dnq.xdParent
import kotlinx.dnq.xdRequiredLongProp
import kotlinx.dnq.xdRequiredStringProp
import kotlinx.dnq.xdStringProp
import kotlinx.dnq.query.and
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.lt
import kotlinx.dnq.query.query
import kotlinx.dnq.query.take
import kotlinx.dnq.query.toList
import org.junit.Test
import java.util.Locale

/** Hub's persistent-job history cleanup without the scheduler or Hub dependencies. */
class PersistentJobCleanupReproducerTest : DBTest() {
    override fun registerEntityTypes() {
        XdModel.registerNodes(UuidObject, ArchivedJob, HistoryUuid)
    }

    abstract class UuidObject(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<UuidObject>()

        var uuid by xdRequiredStringProp(unique = true)
        val history by xdChildren0_N(HistoryUuid::parent)
    }

    class HistoryUuid(entity: Entity) : UuidObject(entity) {
        companion object : XdNaturalEntityType<HistoryUuid>()

        var parent: UuidObject by xdParent(UuidObject::history)
    }

    class ArchivedJob(entity: Entity) : UuidObject(entity) {
        companion object : XdNaturalEntityType<ArchivedJob>() {
            fun expiredBefore(cutoff: Long, limit: Int): List<ArchivedJob> =
                query((ArchivedJob::archivedAtMillis lt cutoff) and (ArchivedJob::typeName eq JOB_TYPE))
                    .take(limit).toList()
        }

        var key by xdRequiredStringProp()
        var statusName by xdRequiredStringProp(dbName = "status")
        var typeName by xdRequiredStringProp(dbName = "type")
        var priorityName by xdRequiredStringProp(dbName = "priority")
        var scheduledAtMillis by xdRequiredLongProp(dbName = "scheduledAt")
        var lastActiveAtMillis by xdRequiredLongProp(dbName = "lastActiveAt")
        var paramsJson: String? by xdStringProp(dbName = "params")
        var errorMessage: String? by xdStringProp()
        var completedAtMillis: Long? by xdNullableLongProp(dbName = "completedAt")
        var nodeId: String? by xdStringProp()
        var archivedAtMillis by xdRequiredLongProp(dbName = "archivedAt")
    }

    @Test
    fun cleanupThousandExpiredJobsInThreeHundredRecordTransactions() {
        // Seed outside the measurement. The inherited history/parent association must be registered:
        // deleting each job probes incoming links even when no history records exist.
        store.transactional {
            repeat(EXPIRED_COUNT) { createJob(it, OLD_TIME) }
            createJob(EXPIRED_COUNT, RECENT_TIME)
        }

        val batchSizes = mutableListOf<Int>()
        var totalFetch = 0L
        var totalDelete = 0L
        var totalTransaction = 0L
        repeat(4) {
            var fetchNanos = 0L
            var deleteNanos = 0L
            val started = System.nanoTime()
            val count = store.transactional {
                val fetchStarted = System.nanoTime()
                val chunk = ArchivedJob.expiredBefore(CUTOFF, BATCH_SIZE)
                fetchNanos = System.nanoTime() - fetchStarted

                val deleteStarted = System.nanoTime()
                chunk.forEach { it.delete() }
                deleteNanos = System.nanoTime() - deleteStarted
                chunk.size
            }
            val transactionNanos = System.nanoTime() - started
            batchSizes.add(count)
            totalFetch += fetchNanos
            totalDelete += deleteNanos
            totalTransaction += transactionNanos
            println("cleanup batch ${batchSizes.size}: count=$count fetch=${ms(fetchNanos)} ms " +
                "delete=${ms(deleteNanos)} ms transaction=${ms(transactionNanos)} ms")
        }

        println("cleanup totals: fetch=${ms(totalFetch)} ms delete=${ms(totalDelete)} ms " +
            "transaction=${ms(totalTransaction)} ms " +
            "remainder=${ms(totalTransaction - totalFetch - totalDelete)} ms")
        assertThat(batchSizes).containsExactly(300, 300, 300, 100).inOrder()
        store.transactional {
            assertThat(ArchivedJob.all().toList().map { it.key }).containsExactly("recent-job")
        }
    }

    private fun createJob(index: Int, archivedAt: Long) {
        ArchivedJob.new {
            uuid = "job-$index"
            key = if (index == EXPIRED_COUNT) "recent-job" else "old-job-$index"
            statusName = "COMPLETED"
            typeName = JOB_TYPE
            priorityName = "NORMAL"
            scheduledAtMillis = archivedAt
            lastActiveAtMillis = archivedAt
            completedAtMillis = archivedAt
            archivedAtMillis = archivedAt
        }
    }

    private fun ms(nanos: Long): String = String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0)

    companion object {
        private const val JOB_TYPE = "AUTH_CODES_CLEANUP"
        private const val EXPIRED_COUNT = 1000
        private const val BATCH_SIZE = 300
        private const val OLD_TIME = 1_000L
        private const val CUTOFF = 2_000L
        private const val RECENT_TIME = 3_000L
    }
}
