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

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Collects a frequency distribution of executed [GremlinQuery] shapes, and the outcome of provider
 * optimization (translated to YouTrackDB MATCH or not) for every executed query.
 *
 * ### JVM-wide collection (across all tests)
 *
 * Configured via JVM system properties — no code changes needed in the app's test suite:
 *
 * - `dnq.query.collector.enabled=true` — activates collection (default: disabled)
 * - `dnq.query.collector.output=<path>` — file to write the report to on JVM exit
 *   (default: stdout). `{pid}` in the path is replaced by the JVM's process id, so JVMs forked by a
 *   test run do not overwrite each other's reports.
 *
 * The report is written automatically via a JVM shutdown hook when the test JVM exits,
 * so the output reflects the full accumulated data across all test classes.
 *
 * The report has two sections. The first lists `[count] <DNQ shape>` per started query. The second, after
 * a `# translation outcomes` header, lists one tab-separated line per distinct outcome: `[count]`,
 * `TRANSLATED` or `NOT_TRANSLATED`, the DNQ shape, the parameterized native Gremlin shape (runtime values
 * are replaced by `_args_n`; untranslated outcomes are distinguished by it, for translated ones it is the
 * first execution's) and an execution example from the first execution seen: the Gremlin script with its
 * runtime values, the final provider traversal and, for translated queries, the MATCH execution plan
 * (newlines escaped as `\n`). Outcomes are recorded when provider strategies are applied, so a query that
 * is built but never iterated has none. Collection only observes queries; it never changes or fails them.
 *
 * Example Gradle test configuration:
 * ```
 * test {
 *     jvmArgs "-Ddnq.query.collector.enabled=true",
 *             "-Ddnq.query.collector.output=/tmp/query-shapes.txt"
 * }
 * ```
 *
 * ### Per-test query counting
 *
 * Individual tests can enable collection programmatically and measure the number of
 * queries matching a given shape predicate within a scoped block:
 *
 * ```kotlin
 * GremlinQueryCollector.enableForTests()
 * val before = GremlinQueryCollector.snapshot()
 * // ... execute the operation under test ...
 * val findLinksCount = GremlinQueryCollector.countSince(before) { "InLink" in it }
 * assertThat(findLinksCount).isEqualTo(expectedCount)
 * ```
 */
object GremlinQueryCollector {

    private const val PROP_ENABLED = "dnq.query.collector.enabled"
    private const val PROP_OUTPUT  = "dnq.query.collector.output"

    private val enabledByProperty: Boolean = System.getProperty(PROP_ENABLED) == "true"

    @Volatile
    private var enabledForTests: Boolean = false

    val enabled: Boolean get() = enabledByProperty || enabledForTests

    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val outcomes = ConcurrentHashMap<OutcomeKey, AtomicInteger>()
    private val examples = ConcurrentHashMap<OutcomeKey, Example>()

    init {
        if (enabledByProperty) {
            Runtime.getRuntime().addShutdownHook(Thread(::writeReport, "gremlin-collector-shutdown"))
        }
    }

    /**
     * Activates collection programmatically, without requiring the system property.
     * Intended for use in individual test methods that want to assert on query counts.
     * Once enabled, collection remains active for the lifetime of the JVM (or test run).
     */
    fun enableForTests() {
        enabledForTests = true
    }

    fun record(shape: String) {
        if (!enabled) return
        counts.computeIfAbsent(shape) { AtomicInteger(0) }.incrementAndGet()
    }

    internal fun recordOutcome(
        shape: String,
        translated: Boolean,
        nativeGremlinShape: () -> String,
        executionExample: () -> String
    ) {
        if (!enabled) return
        // Untranslated outcomes are keyed by their native shape; translated ones only by DNQ shape, so their
        // native shape is rendered for the first execution only (as is the costly execution example).
        val key = OutcomeKey(shape, translated, if (translated) null else nativeGremlinShape())
        outcomes.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
        if (!examples.containsKey(key)) {
            examples.putIfAbsent(key, Example(key.nativeGremlinShape ?: nativeGremlinShape(), executionExample()))
        }
    }

    /**
     * Returns a snapshot of the current per-shape counts.
     * Use this before an operation and pass the result to [countSince] afterwards.
     */
    fun snapshot(): Map<String, Int> =
        counts.entries.associate { it.key to it.value.get() }

    /**
     * Counts the number of query executions since [before] was captured, optionally
     * filtered to shapes matching [filter].
     *
     * @param before a snapshot captured before the operation under measurement
     * @param filter predicate on the shape string; defaults to counting all shapes
     * @return total number of matching query executions since the snapshot
     */
    fun countSince(before: Map<String, Int>, filter: (String) -> Boolean = { true }): Int =
        counts.entries
            .filter { filter(it.key) }
            .sumOf { (shape, counter) -> counter.get() - (before[shape] ?: 0) }

    /** Returns entries sorted by count descending. */
    fun report(): List<ReportEntry> =
        counts.entries
            .sortedByDescending { it.value.get() }
            .map { ReportEntry(it.key, it.value.get()) }

    /** Returns translation outcomes sorted by count descending. */
    fun outcomeReport(): List<OutcomeEntry> =
        outcomes.entries
            .sortedByDescending { it.value.get() }
            .map { (key, count) ->
                val example = examples[key]
                OutcomeEntry(
                    key.shape, key.translated, key.nativeGremlinShape ?: example?.nativeGremlinShape,
                    count.get(), example?.execution
                )
            }

    /** Renders the report exactly as it is written on JVM exit. */
    internal fun reportLines(): List<String> {
        val shapeLines = report().map { (shape, count) -> "[$count] $shape" }
        val outcomeEntries = outcomeReport()
        if (outcomeEntries.isEmpty()) return shapeLines
        val outcomeLines = outcomeEntries.map { entry ->
            listOf(
                "[${entry.count}]",
                if (entry.translated) "TRANSLATED" else "NOT_TRANSLATED",
                entry.shape,
                entry.nativeGremlinShape.orEmpty(),
                entry.executionExample.orEmpty()
            ).joinToString("\t") { it.replace('\t', ' ').replace("\n", "\\n") }
        }
        return shapeLines + "" + OUTCOMES_HEADER + outcomeLines
    }

    private fun writeReport() {
        val lines = reportLines()
        if (lines.isEmpty()) return
        val outputPath = System.getProperty(PROP_OUTPUT)
            ?.replace("{pid}", ProcessHandle.current().pid().toString())
        if (outputPath != null) {
            File(outputPath).writeText(lines.joinToString("\n"))
        } else {
            lines.forEach(::println)
        }
    }

    data class ReportEntry(val shape: String, val count: Int)

    data class OutcomeEntry(
        val shape: String,
        val translated: Boolean,
        val nativeGremlinShape: String?,
        val count: Int,
        val executionExample: String?
    )

    private data class OutcomeKey(val shape: String, val translated: Boolean, val nativeGremlinShape: String?)

    private class Example(val nativeGremlinShape: String, val execution: String)

    private const val OUTCOMES_HEADER =
        "# translation outcomes: [count]<TAB>OUTCOME<TAB>DNQ shape<TAB>native Gremlin shape<TAB>execution example"
}
