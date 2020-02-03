/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
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
package kotlinx.dnq.query

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.junit.Test

class SizeTest : DBTest() {

    class Issue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Issue>()

        val links by xdLink0_N(IssueLink)
    }

    class IssueLink(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<IssueLink>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Issue, IssueLink)
    }

    @Test
    fun testCountAfterRemoveBeforeAndAfterFlush() {
        val issue = transactional { txn ->
            val issue = Issue.new()

            issue.links.add(IssueLink.new())
            assertQuery(issue.links).hasSize(1)

            txn.flush()
            assertQuery(issue.links).hasSize(1)

            issue
        }
        transactional {
            assertQuery(issue.links).hasSize(1)
        }
        transactional { txn ->
            issue.links.clear()
            assertQuery(issue.links).isEmpty()

            txn.flush()
            assertQuery(issue.links).isEmpty()
        }
        transactional {
            assertQuery(issue.links).isEmpty()
        }
    }
}
