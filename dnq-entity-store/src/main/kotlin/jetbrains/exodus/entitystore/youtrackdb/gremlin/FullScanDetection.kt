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
package jetbrains.exodus.entitystore.youtrackdb.gremlin

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A single detected unindexed full-scan query, captured by [FullScanDetectingListener] when
 * [FullScanDetection] is enabled. Used both for the human-readable warn log line and as an
 * in-memory record for test assertions.
 *
 * @param shape the DNQ-level, value-anonymized query description (from `GremlinQueryShape.of`),
 *   ferried into the query via `YTDBQueryConfigParam.querySummary`; may be `null` if no summary
 *   was injected.
 * @param gremlin the executed Gremlin script as reported by YTDB (already anonymized, literals
 *   rendered as `_args_N`).
 * @param elapsedMs the query execution time in milliseconds.
 * @param plan the pretty-printed YTDB execution plan (may contain un-redacted filter values).
 */
data class DetectedScan(
    val shape: String?,
    val gremlin: String,
    val elapsedMs: Long,
    val plan: String
)

/**
 * Configuration and in-memory test sink for XD-1281 unindexed full-scan detection.
 *
 * Mirrors [GremlinQueryCollector] conventions:
 * - Enabled JVM-wide via the system property `dnq.query.fullscan.detection.enabled=true`
 *   (default: disabled).
 * - Can be toggled programmatically from tests via [enableForTests] / [disable].
 *
 * When [enabled], [FullScanDetectingListener] is registered on every transaction begin and, on a
 * detected full scan, logs a warning.
 *
 * ### Detection vs. the in-memory sink
 *
 * [enabled] (system property OR the test toggle) gates *detection + registration + warn logging*.
 * The in-memory [scans] sink, however, is populated by [record] **only in test mode** (i.e. after
 * [enableForTests]) — never when enablement comes solely from the system property. This keeps
 * production activation strictly log-only, so the unbounded queue cannot grow under sustained
 * full-scan traffic (no heap/OOM risk), and makes the sink-based test assertions independent of
 * any externally-set system property.
 *
 * All state is thread-safe: queries may run on multiple threads concurrently.
 */
object FullScanDetection {

    private const val PROP_ENABLED = "dnq.query.fullscan.detection.enabled"

    private val enabledByProperty: Boolean = System.getProperty(PROP_ENABLED) == "true"

    @Volatile
    private var enabledForTests: Boolean = false

    /** Whether detection/registration/warn-logging is active (production or test). */
    val enabled: Boolean get() = enabledByProperty || enabledForTests

    /** Whether the in-memory sink accumulates records. Test-only; never true in production. */
    val testMode: Boolean get() = enabledForTests

    private val scans = ConcurrentLinkedQueue<DetectedScan>()

    /**
     * Activates full-scan detection programmatically, without requiring the system property.
     * Intended for individual test methods that assert on detected scans.
     */
    fun enableForTests() {
        enabledForTests = true
    }

    /** Deactivates the programmatic toggle. Does not affect the system property. */
    fun disable() {
        enabledForTests = false
    }

    /**
     * Records a detected full scan into the in-memory sink. No-op unless in [testMode]:
     * production activation (system property only) is log-only, so the queue never accumulates
     * outside tests.
     */
    fun record(scan: DetectedScan) {
        if (!testMode) return
        scans.add(scan)
    }

    /**
     * Returns a snapshot of all detected scans recorded so far. Use this before an operation and
     * pass the result to [countSince] afterwards.
     */
    fun snapshot(): List<DetectedScan> = scans.toList()

    /** Number of scans recorded since [before] was captured. */
    fun countSince(before: List<DetectedScan>): Int = scans.size - before.size

    /** Clears all recorded scans. Intended for test setup/teardown. */
    fun clear() {
        scans.clear()
    }
}
