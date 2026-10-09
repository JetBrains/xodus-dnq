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
package kotlinx.dnq.query

import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.query.LeafNode
import jetbrains.exodus.query.NodeFactory
import kotlinx.dnq.DBTest
import org.junit.Test

/**
 * Checks the YTDB-1369 regression fixed in YouTrackDB `0.5.0-dev-20261008.022819-302`.
 * Chained label checks after navigation must reject sibling types.
 * Mixed `AND` emission must exclude type filters so they do not become adjacent to an outer label.
 * Only safe property filters may precede link checks for indexed SQL (Structured Query Language) execution.
 *
 * One user links to a `RootGroup` and a sibling `NestedGroup`. Both have the same alias.
 * After navigation, public `XdQuery.query` receives a `NodeFactory` conjunction of alias equality
 * and a `HasLabel` predicate for `RootGroup`. Plain `filterIsInstance` did not reproduce this defect.
 *
 * MATCH is YouTrackDB's pattern query mode translated from Gremlin.
 * This regression runs with MATCH enabled and disabled. This class does not toggle the mode itself.
 */
class FilterIsInstanceAfterNavigationTest : DBTest() {

    /**
     * Navigates through `User.groups`, then combines alias equality with the `RootGroup` label predicate.
     * Only the root must pass. The sibling has the same alias so the type restriction must reject it.
     * This fails if the provider OR-matches the subtype check with the outer `Group` label.
     */
    @Test
    fun `property and subtype filters after navigation exclude sibling`() {
        val root = transactional {
            val user = User.new { login = "owner"; skill = 1 }
            val root = RootGroup.new { name = "root"; alias = "selected" }
            val sibling = NestedGroup.new {
                name = "sibling"
                alias = "selected"
                owner = user
                parentGroup = root
            }
            user.groups.add(root)
            user.groups.add(sibling)
            root
        }

        transactional {
            val roots = User.all()
                .flatMapDistinct(User::groups)
                .filterIsInstance(Group)
                .query(
                    NodeFactory.and(
                        NodeFactory.propEqual("alias", "selected"),
                        LeafNode(GremlinQuery.Where.of(GremlinBlock.HasLabel(RootGroup.entityType)))
                    )
                )
            // The YTDB-1369 regression must reject the sibling NestedGroup.
            assertQuery(roots).containsExactly(root)
        }
    }
}
