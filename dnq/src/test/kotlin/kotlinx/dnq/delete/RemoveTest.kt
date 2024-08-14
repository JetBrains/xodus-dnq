/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
import jetbrains.exodus.database.exceptions.EntityRemovedException
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.*
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertFailsWith

class RemoveTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(RIssue, RIssuePart, RIssueSubpart, RUser, RRole)
    }

    @Test
    @Ignore
    fun removeFromAll() {
        this.store.transactional {
            val user = RUser.new("user").apply {
                this.role = RRole.new()
            }
            RUser.new("user")
            RUser.new("user2")
            RUser.new("user")

            RIssue.new { this.reporter = user }
        }
        this.store.transactional {
            assertThat(RUser.all().toList()).hasSize(4)
        }
        this.store.transactional {
            RUser.query(RUser::name eq "user2")
                    .firstOrNull()
                    ?.delete()
        }
        val (issue, issuePart) = this.store.transactional {
            assertThat(RUser.all().toList()).hasSize(3)

            assertThat(RUser.all().toList().map { it.name })
                    .containsExactly("user", "user", "user")

            assertThat(RIssue.all().toList()).hasSize(1)

            val issue = RIssue.all().first()
            issue.delete()
            assertFailsWith<EntityRemovedException> {
                issue.multipleCascadePart.toList()
            }

            val issuePart = RIssuePart.new()
            issuePart.delete()
            assertFailsWith<EntityRemovedException> {
                issue.multipleCascadePart.remove(issuePart)
            }

            assertThat(RUser.all().toList()).hasSize(3)
            Pair(issue, issuePart)
        }
        this.store.transactional {
            assertFailsWith<EntityRemovedInDatabaseException> {
                issue.multipleCascadePart.clear()
            }

            assertFailsWith<EntityRemovedInDatabaseException> {
                issue.multipleCascadePart.remove(issuePart)
            }

            assertThat(RUser.all().toList()).hasSize(3)
        }
    }

    @Test
    @Ignore
    fun removeFromMultipleAggregation() {
        this.store.transactional {
            val issue = RIssue.new()
            (1..3).forEach {
                issue.multipleCascadePart.add(RIssuePart.new())
            }
        }
        this.store.transactional {
            val issue = RIssue.all().first()
            assertThat(issue.multipleCascadePart.toList()).hasSize(3)
            issue.multipleCascadePart.remove(issue.multipleCascadePart.first())
        }
        this.store.transactional {
            val issue = RIssue.all().first()
            assertThat(issue.multipleCascadePart.toList()).hasSize(2)
            issue.multipleCascadePart.remove(issue.multipleCascadePart.first())
        }
        this.store.transactional {
            val issue = RIssue.all().first()
            assertThat(issue.multipleCascadePart.toList()).hasSize(1)
            issue.multipleCascadePart.remove(issue.multipleCascadePart.first())
        }
        this.store.transactional {
            val issue = RIssue.all().first()
            assertThat(issue.multipleCascadePart.toList()).hasSize(0)
            assertThat(issue.multipleCascadePart.firstOrNull()).isNull()
        }
    }

    @Test
    @Ignore
    fun removeChild() {
        val part = store.transactional {
            val issue = RIssue.new()
            val part = RIssuePart.new()
            issue.multipleCascadePart.add(part)
            part
        }
        store.transactional {
            part.multipleCascadeParent = null
        }
        store.transactional {
            assertThat(RIssuePart.all().toList()).isEmpty()
            assertThat(RIssue.all().toList()).hasSize(1)
        }
    }

}
