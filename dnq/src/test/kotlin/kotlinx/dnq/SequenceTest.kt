/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import org.junit.Test

class SequenceTest : DBTest() {
    class XdProject(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdProject>()

        val nextIssueNumber by xdSequenceProp()
    }

    override fun registerEntityTypes() {
        XdModel.registerNode(XdProject)
    }

    @Test
    fun `increment changes sequence`() {
        val project = transactional {
            XdProject.new()
        }

        assertThat(transactional { project.nextIssueNumber.increment() }).isEqualTo(0)
        assertThat(transactional { project.nextIssueNumber.increment() }).isEqualTo(1)
        assertThat(transactional { project.nextIssueNumber.increment() }).isEqualTo(2)
    }

    @Test
    fun `sequence fields of different fields should be independent`() {
        val (project1, project2) = transactional {
            Pair(XdProject.new(), XdProject.new())
        }

        transactional {
            project1.nextIssueNumber.increment()
            project1.nextIssueNumber.increment()
        }
        assertThat(transactional { project1.nextIssueNumber.get() }).isEqualTo(1)
        assertThat(transactional { project2.nextIssueNumber.get() }).isEqualTo(-1)
    }

    @Test
    fun `sequence field should be concurrent`() {
        val project = transactional {
            XdProject.new()
        }

        transactional {
            assertThat(project.nextIssueNumber.increment()).isEqualTo(0)
            transactional(isNew = true) {
                assertThat(project.nextIssueNumber.increment()).isEqualTo(1)
            }
            assertThat(project.nextIssueNumber.get()).isEqualTo(1)
        }
    }

    @Test
    fun `sequence field should be incremented event if transaction was reverted`() {
        val project = transactional {
            XdProject.new()
        }
        transactional { txn ->
            project.nextIssueNumber.increment()
            project.nextIssueNumber.increment()
            project.nextIssueNumber.increment()
            txn.revert()
        }

        assertThat(transactional { project.nextIssueNumber.get() }).isEqualTo(2)
    }

    @Test
    fun `sequence field should be settable`() {
        val project = transactional {
            XdProject.new()
        }
        transactional {
            project.nextIssueNumber.set(2)
            project.nextIssueNumber.increment()
        }

        assertThat(transactional { project.nextIssueNumber.get() }).isEqualTo(3)
    }
}