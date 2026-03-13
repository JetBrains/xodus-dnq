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
package kotlinx.dnq.query.coverage

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.toList
import org.junit.Before
import org.junit.Test

class IssueTrackerDatasetTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(XdUser, Employee, Manager, Project, Issue, Sprint, Tag)
    }

    private lateinit var dataset: IssueTrackerDataset

    @Before
    fun setupDataset() {
        dataset = IssueTrackerDataset(store)
    }

    @Test
    fun `dataset creates 24 issues`() {
        store.transactional {
            assertThat(Issue.all().toList()).hasSize(24)
        }
    }

    @Test
    fun `dataset creates 4 projects`() {
        store.transactional {
            assertThat(Project.all().toList()).hasSize(4)
        }
    }

    @Test
    fun `dataset creates 3 sprints`() {
        store.transactional {
            assertThat(Sprint.all().toList()).hasSize(3)
        }
    }

    @Test
    fun `dataset creates 3 tags`() {
        store.transactional {
            assertThat(Tag.all().toList()).hasSize(3)
        }
    }

    @Test
    fun `dataset creates 5 users including employees and manager`() {
        store.transactional {
            assertThat(XdUser.all().toList()).hasSize(5)
            assertThat(Employee.all().toList()).hasSize(4) // Alice, Bob, Carol, Eve (Dave is plain User)
            assertThat(Manager.all().toList()).hasSize(1)  // Eve
        }
    }

    @Test
    fun `issue project links are correct`() {
        store.transactional {
            assertThat(dataset.issues["ENG-1"]!!.project?.key).isEqualTo("ENG")
            assertThat(dataset.issues["OPS-1"]!!.project?.key).isEqualTo("OPS")
            assertThat(dataset.issues["ARC-1"]!!.project?.key).isEqualTo("ARC")
        }
    }

    @Test
    fun `ENG project has 14 issues`() {
        store.transactional {
            assertThat(dataset.projects["ENG"]!!.issues.toList()).hasSize(14)
        }
    }

    @Test
    fun `issue assignee links are correct`() {
        store.transactional {
            assertThat(dataset.issues["ENG-1"]!!.assignee?.name).isEqualTo("Alice")
            assertThat(dataset.issues["ENG-7"]!!.assignee?.name).isEqualTo("Eve")
            assertThat(dataset.issues["ENG-6"]!!.assignee).isNull()
        }
    }

    @Test
    fun `issue tag links are correct`() {
        store.transactional {
            val bugIssueKeys = dataset.issues.values
                .filter { it.tags.toList().any { tag -> tag.name == "bug" } }
                .map { it.key }
                .sorted()
            assertThat(bugIssueKeys)
                .containsExactly("ENG-1", "ENG-10", "ENG-2", "ENG-6", "INFRA-3", "OPS-1", "OPS-4")
        }
    }

    @Test
    fun `parent-child issue links are correct`() {
        store.transactional {
            assertThat(dataset.issues["ENG-12"]!!.parent?.key).isEqualTo("ENG-3")
            assertThat(dataset.issues["ENG-14"]!!.parent?.key).isEqualTo("ENG-5")
            assertThat(dataset.issues["ENG-1"]!!.parent).isNull()
        }
    }

    @Test
    fun `project lead links are correct`() {
        store.transactional {
            assertThat(dataset.projects["ENG"]!!.lead?.name).isEqualTo("Alice")
            assertThat(dataset.projects["OPS"]!!.lead?.name).isEqualTo("Carol")
            assertThat(dataset.projects["ARC"]!!.lead).isNull()
        }
    }

    @Test
    fun `sprint project links are correct`() {
        store.transactional {
            assertThat(dataset.sprints["S1"]!!.project?.key).isEqualTo("ENG")
            assertThat(dataset.sprints["S3"]!!.project?.key).isEqualTo("OPS")
        }
    }
}
