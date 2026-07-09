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
package jetbrains.exodus.entitystore.youtrackdb.iterate

import com.google.common.truth.Truth.assertThat
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import jetbrains.exodus.entitystore.youtrackdb.getOrCreateVertexClass
import jetbrains.exodus.entitystore.youtrackdb.gremlin.FullScanDetection
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.Issues
import jetbrains.exodus.entitystore.youtrackdb.testutil.OTestMixin
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * XD-1281: verifies that queries performing an unindexed full scan are detected and recorded by
 * [FullScanDetection], while indexed queries are not, and that the feature is inert when disabled.
 *
 * Assertions run against the [FullScanDetection] in-memory sink (never against log output).
 * Because [FullScanDetection] is a JVM-global object with a shared sink, each test enables the
 * feature only after its fixture/schema setup, clears the sink immediately before the measured
 * query, and the [@After] hook resets global state so tests cannot leak into one another.
 */
class FullScanLoggingTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB()

    override val youTrackDb = orientDbRule

    @After
    fun resetDetection() {
        FullScanDetection.disable()
        FullScanDetection.clear()
    }

    /** Declares [property] as a STRING and builds a NOTUNIQUE index over it on the Issue class. */
    private fun indexIssueProperty(property: String) {
        val session = youTrackDb.openSession()
        try {
            val oClass = session.getOrCreateVertexClass(Issues.CLASS)
            oClass.createProperty(property, PropertyType.STRING)
            oClass.createIndex("idx_issue_$property", SchemaClass.INDEX_TYPE.NOTUNIQUE, property)
        } finally {
            session.close()
        }
    }

    @Test
    fun `unindexed query is detected as a full scan`() {
        val test = givenTestCase() // issue1..3 on an unindexed 'name' property

        FullScanDetection.enableForTests()
        FullScanDetection.clear()

        withStoreTx { tx ->
            // Iterate to exhaustion via toList(). DNQ closes the traversal twice (TinkerPop
            // auto-close on exhaustion + explicit dispose), which fires queryFinished twice; the
            // listener's back-to-back dedup collapses this to exactly one record. This case is the
            // regression guard for the double-fire: without dedup it would record 2.
            tx.find(Issues.CLASS, "name", "issue1").toList()
        }

        val scans = FullScanDetection.snapshot()
        assertThat(scans).hasSize(1)
        assertThat(scans.single().plan).contains("FETCH FROM CLASS")
    }

    @Test
    fun `indexed query is not detected as a full scan`() {
        indexIssueProperty("name")
        val test = givenTestCase() // issue1..3

        FullScanDetection.enableForTests()
        FullScanDetection.clear()

        withStoreTx { tx ->
            tx.find(Issues.CLASS, "name", "issue1").toList()
        }

        assertThat(FullScanDetection.snapshot()).isEmpty()
    }

    @Test
    fun `detected scan carries anonymized query, shape, timing and plan`() {
        val test = givenTestCase() // issue1..3 on an unindexed 'name' property

        FullScanDetection.enableForTests()
        FullScanDetection.clear()

        withStoreTx { tx ->
            tx.find(Issues.CLASS, "name", "issue1").toList()
        }

        assertThat(FullScanDetection.snapshot()).hasSize(1)
        val scan = FullScanDetection.snapshot().single()

        // Executed Gremlin is anonymized by YTDB (literal values -> _args_N placeholders).
        assertThat(scan.gremlin).contains("_args_")
        // DNQ-supplied shape is present and value-anonymized (literals rendered as '?').
        assertThat(scan.shape).isNotNull()
        assertThat(scan.shape).contains("?")
        // Timing is a real, non-negative elapsed duration.
        assertThat(scan.elapsedMs).isAtLeast(0L)
        // Plan text is non-empty and names the scanned class.
        assertThat(scan.plan).isNotEmpty()
        assertThat(scan.plan).contains("FETCH FROM CLASS")
        assertThat(scan.plan).contains(Issues.CLASS)
    }

    @Test
    fun `no detection when the feature is disabled`() {
        val test = givenTestCase() // issue1..3 on an unindexed 'name' property

        // Deliberately do NOT enable detection (default off). Assert hermeticity: no test-mode
        // leakage from a prior test and an empty sink before the measured query.
        FullScanDetection.clear()
        assertThat(FullScanDetection.testMode).isFalse()
        assertThat(FullScanDetection.snapshot()).isEmpty()

        withStoreTx { tx ->
            tx.find(Issues.CLASS, "name", "issue1").toList()
        }

        assertThat(FullScanDetection.snapshot()).isEmpty()
    }
}
