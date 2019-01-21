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
package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import org.junit.Test

class AutoMergeTest : DBTest() {

    class Project(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Project>()

        val issues by xdLink0_N(Issue::project)
    }

    class Issue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Issue>() {
            fun new(name: String) = new {
                this.name = name
            }
        }

        var name by xdStringProp()
        var project: Project by xdParent(Project::issues)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Project, Issue)
    }

    @Test
    fun testAddIssueToProject() {
        val p = transactional { Project.new() }
        val (i1, i2) = store.runTranAsyncAndJoin {
            val i1 = Issue.new("issue1")
            p.issues.add(i1)
            val i2 = store.runTranAsyncAndJoin {
                val i2 = Issue.new("issue2")
                p.issues.add(i2)
                i2
            }
            Pair(i1, i2)
        }
        transactional {
            assertQuery(p.issues).containsExactly(i1, i2)
        }
    }
}
