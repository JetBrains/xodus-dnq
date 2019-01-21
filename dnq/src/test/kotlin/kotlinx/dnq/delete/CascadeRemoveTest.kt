/**
 * Copyright 2006 - 2019 JetBrains s.r.o.
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
import org.junit.Test

class CascadeRemoveTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(RIssue, RIssuePart, RIssueSubpart, RUser, RRole)
    }

    @Test
    fun removeParentWithSingleChildWithCascadeDelete() {
        val issue = store.transactional {
            val part = RIssuePart.new()
            RIssue.new { singleCascadePart = part }
        }
        store.transactional {
            issue.delete()
        }
        store.transactional {
            assertThat(RIssue.all().toList()).isEmpty()
            assertThat(RIssuePart.all().toList()).isEmpty()
        }
    }

    @Test
    fun removeParentWithMultipleChildWithCascadeDelete() {
        val issue = store.transactional {
            val issue = RIssue.new()
            val part1 = RIssuePart.new()
            val part2 = RIssuePart.new()
            issue.multipleCascadePart.add(part1)
            part2.multipleCascadeParent = issue
            issue
        }
        store.transactional {
            issue.delete()
        }
        store.transactional {
            assertThat(RIssue.all().toList()).isEmpty()
            assertThat(RIssuePart.all().toList()).isEmpty()
        }
    }

    @Test
    fun removeCascadeByCascadeDelete() {
        val issue = store.transactional {
            val issue = RIssue.new()
            val part1 = RIssuePart.new {
                subparts.add(RIssueSubpart.new())
            }
            val part2 = RIssuePart.new {
                subparts.add(RIssueSubpart.new())
            }
            issue.multipleCascadePart.add(part1)
            part2.multipleCascadeParent = issue

            issue
        }
        store.transactional {
            issue.delete()
        }
        store.transactional {
            assertThat(RIssue.all().toList()).isEmpty()
            assertThat(RIssuePart.all().toList()).isEmpty()
            assertThat(RIssueSubpart.all().toList()).isEmpty()
        }
    }
}
