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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.*
import org.junit.Test

/**
 * Xodus iterates a link in ascending target `(typeId, localId)` order, which for same-type targets
 * is creation order. YouTrackDB's adjacency is a `LinkBag` keyed by *edge* RID, so a raw traversal
 * returns neither creation order nor a flush-stable one. These tests pin the Xodus contract for
 * every read path a to-many link delegate can take.
 *
 * Every case creates the targets in name order (so `A < B < ... < E` by `localEntityId`) and only
 * *then* attaches them to the link in the scrambled order [attachOrder], so neither the insertion
 * order nor its reverse coincides with the expected result. Five targets are used rather than the
 * minimum three: an arbitrary permutation of five coincides with the sorted one only 1 time in 120.
 *
 * See `GremlinBlock.LocalIdAsc` and `YTDBVertexEntity.getLinks`.
 */
class LinkOrderTest : DBTest() {

    /** Target of a *directed* to-many link — the one delegate kind [DBTest] does not model. */
    class Note(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Note>()

        var text by xdRequiredStringProp()
    }

    class Board(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Board>()

        var title by xdRequiredStringProp()

        /** `XdToManyLink` — directed, no opposite field. */
        val notes by xdLink0_N(Note)
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(Board, Note)
    }

    private val names = listOf("A", "B", "C", "D", "E")
    private val attachOrder = listOf("E", "C", "A", "D", "B")

    /**
     * Creates one target per name in [names] order — so ids ascend with the name — and attaches them
     * to this link in [attachOrder].
     */
    private fun <T : XdEntity> XdMutableQuery<T>.addScrambled(
        keys: List<String> = names,
        create: (String) -> T
    ) {
        val created = keys.associateWith(create)
        attachOrder.filter { it in created }.forEach { add(created.getValue(it)) }
    }

    private fun createUser(login: String) = User.new { this.login = login; skill = 1 }

    private fun email(name: String) = "${name.lowercase()}@example.com"

    // region one-to-many (XdOneToManyLink, Gremlin path) — Group.nestedGroups

    /** Case 1: created and read in the *same* transaction. */
    @Test
    fun `one to many link is ordered by id within the creating transaction`() {
        store.transactional {
            val owner = createUser("owner")
            val root = RootGroup.new { name = "Root" }
            root.nestedGroups.addScrambled { NestedGroup.new { name = it; this.owner = owner } }

            assertThat(root.nestedGroups.toList().map { it.name })
                .containsExactlyElementsIn(names).inOrder()
        }
    }

    /** Case 2: created in one transaction, read in another. */
    @Test
    fun `one to many link is ordered by id after commit`() {
        store.transactional {
            val owner = createUser("owner")
            val root = RootGroup.new { name = "Root" }
            root.nestedGroups.addScrambled { NestedGroup.new { name = it; this.owner = owner } }
        }

        store.transactional {
            val root = RootGroup.query(RootGroup::name eq "Root").first()
            assertThat(root.nestedGroups.toList().map { it.name })
                .containsExactlyElementsIn(names).inOrder()
        }
    }

    /**
     * Case 3: committed targets and targets created in the reading transaction are ordered together
     * — the case where a persistent RID is compared with a temporary one.
     */
    @Test
    fun `one to many link orders committed and freshly created targets together`() {
        val (root, owner) = store.transactional {
            val owner = createUser("owner")
            val root = RootGroup.new { name = "Root" }
            root.nestedGroups.addScrambled(names.take(3)) {
                NestedGroup.new { name = it; this.owner = owner }
            }
            root to owner
        }

        store.transactional {
            root.nestedGroups.addScrambled(names.drop(3)) {
                NestedGroup.new { name = it; this.owner = owner }
            }

            assertThat(root.nestedGroups.toList().map { it.name })
                .containsExactlyElementsIn(names).inOrder()
        }
    }

    // endregion

    /** Case 4: many-to-many (`XdManyToManyLink`, Gremlin path) — target type has no subtypes. */
    @Test
    fun `many to many link is ordered by id`() {
        val group = store.transactional {
            val group = RootGroup.new { name = "Root" }
            group.users.addScrambled { createUser(it) }

            assertThat(group.users.toList().map { it.login })
                .containsExactlyElementsIn(names).inOrder()
            group
        }

        store.transactional {
            assertThat(group.users.toList().map { it.login })
                .containsExactlyElementsIn(names).inOrder()
        }
    }

    /** Case 5: parent-to-many-children (`XdParentToManyChildrenLink`, Gremlin path). */
    @Test
    fun `parent to children link is ordered by id`() {
        val team = store.transactional {
            val team = Team.new { name = "Team" }
            team.fellows.addScrambled { Fellow.new { name = it } }
            team
        }

        store.transactional {
            assertThat(team.fellows.toList().map { it.name })
                .containsExactlyElementsIn(names).inOrder()
        }
    }

    /**
     * Case 6: the target type has subtypes, so the delegate falls back to `getLinks` →
     * `PersistentEntityIterableWrapper` → `unwrap()` → `YTDBVertexEntityIterable.asQueryIterable`.
     *
     * Only a *single* concrete subtype is linked on purpose: `localEntityId` is a per-type sequence
     * and the fallback query is not scoped to one label, so order across concrete types is not part
     * of the contract.
     */
    @Test
    fun `many to many link with polymorphic target is ordered by id`() {
        val user = store.transactional {
            val user = createUser("user")
            val root = RootGroup.new { name = "Root" }
            user.groups.addScrambled {
                NestedGroup.new { name = it; owner = user; parentGroup = root }
            }

            assertThat(user.groups.toList().map { it.name })
                .containsExactlyElementsIn(names).inOrder()
            user
        }

        store.transactional {
            assertThat(user.groups.toList().map { it.name })
                .containsExactlyElementsIn(names).inOrder()
        }
    }

    /**
     * Case 7: derived operations keep the link order. `skip`/`take`/`intersect` are served by a
     * query, so they only agree with a plain read if that query declares the same sort.
     */
    @Test
    fun `slicing and intersecting a link read keep the link order`() {
        val user = store.transactional {
            val user = createUser("user")
            user.contacts.addScrambled { Contact.new { email = email(it) } }
            user
        }

        store.transactional {
            assertThat(user.contacts.toList().map { it.email })
                .containsExactlyElementsIn(names.map(::email)).inOrder()
            assertThat(user.contacts.take(2).toList().map { it.email })
                .containsExactlyElementsIn(names.take(2).map(::email)).inOrder()
            assertThat(user.contacts.drop(3).toList().map { it.email })
                .containsExactlyElementsIn(names.drop(3).map(::email)).inOrder()
            assertThat((user.contacts intersect Contact.all()).toList().map { it.email })
                .containsExactlyElementsIn(names.map(::email)).inOrder()
        }
    }

    /** Case 8: the raw transient-layer `getLinks` read, as callers outside the DSL use it. */
    @Test
    fun `transient entity getLinks is ordered by id`() {
        val user = store.transactional {
            val user = createUser("user")
            user.contacts.addScrambled { Contact.new { email = email(it) } }
            user
        }

        store.transactional {
            assertThat(user.entity.getLinks("contacts").map { it.getProperty("email") })
                .containsExactlyElementsIn(names.map(::email)).inOrder()
        }
    }

    /** Case 10: directed to-many link (`XdToManyLink`), which reads through `getLinks` too. */
    @Test
    fun `directed to many link is ordered by id`() {
        val board = store.transactional {
            val board = Board.new { title = "Board" }
            board.notes.addScrambled { Note.new { text = it } }

            assertThat(board.notes.toList().map { it.text })
                .containsExactlyElementsIn(names).inOrder()
            board
        }

        store.transactional {
            assertThat(board.notes.toList().map { it.text })
                .containsExactlyElementsIn(names).inOrder()
        }
    }
}
