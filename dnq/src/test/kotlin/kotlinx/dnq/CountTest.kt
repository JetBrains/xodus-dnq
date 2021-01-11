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
import kotlinx.dnq.query.size
import kotlinx.dnq.query.toList
import org.junit.Test

class CountTest : DBTest() {

    class XdProject(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdProject>()

        val issues by xdLink0_N(XdIssue::project)
    }

    class XdIssue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdIssue>()

        var project: XdProject by xdLink1(XdProject::issues)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(XdProject, XdIssue)
    }

    @Test
    fun `property size`() {
        val project = transactional {
            XdProject.new {
                issues.add(XdIssue.new())
                issues.add(XdIssue.new())
            }
        }

        transactional {
            assertThat(project.issues.toList()).hasSize(2)
        }

        transactional {
            assertThat(project.issues.size()).isEqualTo(2)
        }

        transactional {
            assertThat(XdIssue.all().size()).isEqualTo(2)
        }
    }

}
