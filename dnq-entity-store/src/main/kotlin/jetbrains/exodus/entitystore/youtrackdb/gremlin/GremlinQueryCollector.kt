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
 * Collects a frequency distribution of executed [GremlinQuery] shapes.
 *
 * Configured via JVM system properties — no code changes needed in the app's test suite:
 *
 * - `dnq.query.collector.enabled=true` — activates collection (default: disabled)
 * - `dnq.query.collector.output=<path>` — file to write the report to on JVM exit
 *   (default: stdout)
 *
 * The report is written automatically via a JVM shutdown hook when the test JVM exits,
 * so the output reflects the full accumulated data across all test classes.
 *
 * Example Gradle test configuration:
 * ```
 * test {
 *     jvmArgs "-Ddnq.query.collector.enabled=true",
 *             "-Ddnq.query.collector.output=/tmp/query-shapes.txt"
 * }
 * ```
 */
object GremlinQueryCollector {

    private const val PROP_ENABLED = "dnq.query.collector.enabled"
    private const val PROP_OUTPUT  = "dnq.query.collector.output"

    val enabled: Boolean = System.getProperty(PROP_ENABLED) == "true"

    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    init {
        if (enabled) {
            Runtime.getRuntime().addShutdownHook(Thread(::writeReport, "gremlin-collector-shutdown"))
        }
    }

    fun record(shape: String) {
        if (!enabled) return
        counts.computeIfAbsent(shape) { AtomicInteger(0) }.incrementAndGet()
    }

    /** Returns entries sorted by count descending. */
    fun report(): List<ReportEntry> =
        counts.entries
            .sortedByDescending { it.value.get() }
            .map { ReportEntry(it.key, it.value.get()) }

    private fun writeReport() {
        val entries = report()
        if (entries.isEmpty()) return
        val lines = entries.map { (shape, count) -> "[$count] $shape" }
        val outputPath = System.getProperty(PROP_OUTPUT)
        if (outputPath != null) {
            File(outputPath).writeText(lines.joinToString("\n"))
        } else {
            lines.forEach(::println)
        }
    }

    data class ReportEntry(val shape: String, val count: Int)
}
