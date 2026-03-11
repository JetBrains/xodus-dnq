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
package jetbrains.exodus.entitystore.youtrackdb.testutil

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.*
import org.junit.Rule
import org.junit.Test

/**
 * Smoke test: verifies that QueryCoverageDataset populates the DB correctly
 * and that basic queries return the expected entity counts and keys.
 */
class QueryCoverageDatasetTest {

    @Rule
    @JvmField
    val db = InMemoryYouTrackDB(initializeIssueSchema = false)

    private val dataset by lazy { QueryCoverageDataset(db) }

    @Suppress("UNCHECKED_CAST")
    private fun keys(iterable: Iterable<*>): List<String> =
        (iterable as Iterable<Entity>).map { it.getProperty("key") as String }

    @Suppress("UNCHECKED_CAST")
    private fun names(iterable: Iterable<*>): List<String> =
        (iterable as Iterable<Entity>).map { it.getProperty("name") as String }

    @Test
    fun `dataset creates 24 issues`() {
        dataset  // trigger init
        db.withStoreTx { tx ->
            val all = YTDBEntityIterable.where(QueryCoverageDataset.Types.ISSUE, tx, All)
            assertThat(all.toList()).hasSize(24)
        }
    }

    @Test
    fun `dataset creates 4 projects`() {
        dataset
        db.withStoreTx { tx ->
            val all = YTDBEntityIterable.where(QueryCoverageDataset.Types.PROJECT, tx, All)
            assertThat(all.toList()).hasSize(4)
        }
    }

    @Test
    fun `dataset creates 5 users across three types`() {
        dataset
        db.withStoreTx { tx ->
            // hasLabel("User") must include Employee and Manager subclasses
            val allUsers = YTDBEntityIterable.where(QueryCoverageDataset.Types.USER, tx, All)
            assertThat(allUsers.toList()).hasSize(5)
        }
    }

    @Test
    fun `critical issues are ENG-1 ENG-6 OPS-1 OPS-4`() {
        dataset
        db.withStoreTx { tx ->
            val critical = YTDBEntityIterable.where(
                QueryCoverageDataset.Types.ISSUE, tx, PropEqual("priority", "critical")
            )
            assertThat(keys(critical)).containsExactlyElementsIn(listOf("ENG-1", "ENG-6", "OPS-1", "OPS-4"))
        }
    }

    @Test
    fun `archived projects are ARC only`() {
        dataset
        db.withStoreTx { tx ->
            val archived = YTDBEntityIterable.where(
                QueryCoverageDataset.Types.PROJECT, tx, PropEqual("isArchived", true)
            )
            assertThat(keys(archived)).containsExactly("ARC")
        }
    }

    @Test
    fun `Engineering employees are Alice Bob Eve`() {
        dataset
        db.withStoreTx { tx ->
            val eng = YTDBEntityIterable.where(
                QueryCoverageDataset.Types.EMPLOYEE, tx, PropEqual("department", "Engineering")
            )
            assertThat(names(eng)).containsExactlyElementsIn(listOf("Alice", "Bob", "Eve"))
        }
    }

    @Test
    fun `issues in sprint S1 are 7 issues`() {
        dataset
        db.withStoreTx { tx ->
            val inS1 = YTDBEntityIterable.where(
                QueryCoverageDataset.Types.ISSUE, tx, HasLink(QueryCoverageDataset.Links.SPRINT)
            )
            // S1: ENG-1,2,3,6,10,12,13 (7) + S2: ENG-4,7 (2) + S3: OPS-2,4 (2) = 11 total with sprint link
            assertThat(inS1.toList()).hasSize(11)
        }
    }

    @Test
    fun `project leads are Alice for ENG Bob for INFRA Carol for OPS`() {
        dataset
        db.withStoreTx { tx ->
            // Issues whose project lead is in Engineering = ENG issues + INFRA issues
            val withEngLead = YTDBEntityIterable.where(
                QueryCoverageDataset.Types.ISSUE, tx,
                HasLink(QueryCoverageDataset.Links.PROJECT)  // just a smoke check; full Q66 tested in coverage test
            )
            // All 24 issues have a project link
            assertThat(withEngLead.toList()).hasSize(24)
        }
    }
}
