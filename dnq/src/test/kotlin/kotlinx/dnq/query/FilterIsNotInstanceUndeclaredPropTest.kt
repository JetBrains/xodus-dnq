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

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.asYTDBIterable
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.xdStringProp
import org.junit.Test

/**
 * `XdQuery<Parent>.exclude(Child.all())` and `XdQuery<Parent>.filterIsNotInstance(Child)` are
 * documented as equivalent ways of producing "all parents that are not children". When followed
 * by `.filter { it.<prop> eq null }` they should return the same result.
 *
 * They don't, when `<prop>` is declared as an extension on the abstract `XdEntity` and is not
 * registered in the per-class schema of any concrete subtype. The test below sets up exactly that
 * scenario (mirroring YouTrack's `HubUuidModelPlugin` with `typeExtensions = emptyList()`):
 *
 *  - `Parent.all().exclude(Child.all()).filter { it.tag eq null }` returns the 2 parents
 *  - `Parent.all().filterIsNotInstance(Child).filter { it.tag eq null }` returns 0
 *
 * The materialised-then-in-memory form
 * `Parent.all().filterIsNotInstance(Child).toList().filter { it.tag == null }`
 * returns the correct 2 — confirming the values are persisted, the property is readable, and the
 * divergence is at the level of how `filterIsNotInstance` composes with a subsequent `eq null`
 * predicate into a single Gremlin pipeline.
 *
 * Real-world impact (YouTrack `feature-youtrackdb-migration`): commit `9bd2798107a9` (JT-95185)
 * changed `XdUser.allUsers` from `all().exclude(XdSuperUser.all())` to
 * `all().filterIsNotInstance(XdSuperUser)`. The user-export step at startup runs
 * `XdUser.allUsers.filter { it.hubUuid eq null }.toList()`. After JT-95185, on a fresh DB, that
 * call returned an empty list — the admin and guest users were silently skipped during the
 * Hub-side bulk export, the guest never received a Hub UUID, and the subsequent
 * `userService.guest.hubUuidNotNull` call threw, breaking admin/admin login.
 */
// Property declared as an extension on the abstract `XdEntity`. No XdModelPlugin registers it for
// any concrete subtype, so `_tag_` is not entered into Parent's or Child's per-class YouTrackDB
// schema — exactly the configuration of YouTrack's `HubUuidModelPlugin.typeExtensions = emptyList()`.
var XdEntity.tag by xdStringProp(dbName = "_tag_")

class FilterIsNotInstanceUndeclaredPropTest : DBTest() {

    open class Parent(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Parent>()
    }

    class Child(entity: Entity) : Parent(entity) {
        companion object : XdNaturalEntityType<Child>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Parent, Child)
    }

    @Test
    fun `exclude and filterIsNotInstance compose differently with eq null on undeclared prop`() {
        transactional {
            Parent.new()
            Parent.new()
            Child.new()
            Child.new()
        }

        transactional {
            // Sanity: the two parent-only forms agree before the predicate is added.
            assertThat(Parent.all().toList()).hasSize(4)
            assertThat(Parent.all().exclude(Child.all()).toList()).hasSize(2)
            assertThat(Parent.all().filterIsNotInstance(Child).toList()).hasSize(2)

            // None of the 2 parent vertices has `tag` set, so the predicate `tag eq null`
            // is satisfied by both. Both DSL-composed forms must therefore return 2.
            val excludeQuery = Parent.all().exclude(Child.all()).filter { it.tag eq null }
            val filterIsNotInstanceQuery = Parent.all().filterIsNotInstance(Child).filter { it.tag eq null }
            // Surface the actual GremlinQuery the engine builds for each form, so the divergence
            // is visible in test output. The exclude form goes through a NESTED Aggregate; the
            // filterIsNotInstance form fuses everything into one labeled And-Where chain.
            println("[QUERY] exclude form:           ${excludeQuery.entityIterable.let { (it as jetbrains.exodus.entitystore.EntityIterable).asYTDBIterable().query }}")
            println("[QUERY] filterIsNotInstance:    ${filterIsNotInstanceQuery.entityIterable.let { (it as jetbrains.exodus.entitystore.EntityIterable).asYTDBIterable().query }}")
            val excludeForm = excludeQuery.toList()
            val filterIsNotInstanceForm = filterIsNotInstanceQuery.toList()

            // Ground truth: the predicate is correct when applied in memory.
            val materialisedThenKotlinFilter = Parent.all().filterIsNotInstance(Child).toList()
                .filter { it.tag == null }
            assertThat(materialisedThenKotlinFilter).hasSize(2)

            // The bug: filterIsNotInstance composes into a single Gremlin pipeline of the form
            // `g.V().hasLabel("Parent").not(__.hasLabel("Child")).hasNot("_tag_")`, which evaluates
            // to the empty set under YouTrackDB's strict per-class schema (no `_tag_` entry on
            // Parent's class). The exclude form materialises the set difference at the engine
            // level and applies the predicate against the realised iterable, so the predicate
            // works.
            assertThat(excludeForm).hasSize(2)
            assertThat(filterIsNotInstanceForm).hasSize(2)
        }
    }
}
