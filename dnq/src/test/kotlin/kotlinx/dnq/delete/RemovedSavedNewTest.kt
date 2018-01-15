/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.delete


import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.toList
import kotlinx.dnq.transactional
import org.junit.Test

class RemovedSavedNewTest : DBTest() {
    override fun registerEntityTypes() {
        XdModel.registerNodes(
                RIssue, RIssuePart, RIssueSubpart, RUser, RRole,
                RMockProject, RMockProjectField, RMockPrototype)
    }

    @Test
    fun removeIssueInSavedNewState() {
        store.transactional { txn ->
            val i = RIssue.new()
            txn.flush()

            i.delete()
            txn.flush()
            assertThat(RIssue.all().toList()).isEmpty()
        }
    }

    @Test
    fun clearLinkToEntityInRemovedSavedNewState() {
        val (projectA, assigneeFieldPrototype) = store.transactional { txn ->
            val projectA = RMockProject.new()
            val projectB = RMockProject.new()

            val assigneeFieldPrototype = RMockPrototype.new()
            val typeFieldPrototype = RMockPrototype.new()

            // create test field in state New
            val assigneeInA = RMockProjectField.new(assigneeFieldPrototype, projectA)

            // create auxiliary fields to populate links
            RMockProjectField.new(assigneeFieldPrototype, projectB)
            RMockProjectField.new(typeFieldPrototype, projectA)

            // after flush test field state becomes SavedNew
            txn.flush()
            assigneeInA.delete()

            Pair(projectA, assigneeFieldPrototype)
        }
        store.transactional {
            // check that only Type field attached to projectA
            assertThat(projectA.fields.toList()).hasSize(1)

            // check that only field BAssignee is instance of assignee prototype
            assertThat(assigneeFieldPrototype.instances.toList()).hasSize(1)
        }
    }
}
