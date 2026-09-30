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
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.toList
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Contract of attaching an entity to the current session (`TransientStoreSession.checkAttached`,
 * called `newLocalCopy` on the Xodus-based line; reached through `reattach`): using an entity that
 * another transaction has physically deleted must be detected when the entity is *attached*, before
 * any change is applied or queued, and must be reported as [EntityRemovedInDatabaseException].
 *
 * The Xodus-based DNQ guaranteed this by loading the persistent entity on attach.
 * `TransientEntitiesUpdaterImpl.setManyToOne` additionally relies on it (through its private
 * `attachedOrNull`, `newLocalCopySafe` on the Xodus-based line) to skip or clear an association
 * whose endpoint disappeared while the transaction was being replayed after a concurrent commit.
 *
 * These tests use only public Xd/session API that has the same shape on both lines, so they run
 * unchanged on the Xodus-based line.
 */
class StaleEntityContractTest : DBTest() {

    class Owner(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Owner>()

        var name by xdStringProp()
        var note by xdStringProp()
        val children: XdMutableQuery<Child> by xdLink0_N(Child::owner)
        val tags: XdMutableQuery<Tag> by xdLink0_N(Tag::owners)
    }

    class Child(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Child>()

        var owner: Owner? by xdLink0_1(Owner::children)
    }

    class Tag(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Tag>()

        val owners: XdMutableQuery<Owner> by xdLink0_N(Owner::tags)
    }

    /** Directed link, so that assigning it does not go through the many-to-one replay path. */
    class Ticket(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Ticket>()

        var reporter: Owner? by xdLink0_1(Owner)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Owner, Child, Tag, Ticket)
    }

    private fun deletedOwner(): Owner {
        val owner = transactional { Owner.new() }
        transactional { owner.delete() }
        return owner
    }

    // --- setManyToOne replay: an endpoint deleted by the concurrent winner ---

    @Test
    fun `replay of a many-to-one assignment ignores a many endpoint deleted concurrently`() {
        val owner = transactional { Owner.new() }
        val child = transactional { Child.new() }

        transactional {
            child.owner = owner
            // Commits first, so this transaction's flush is replayed on the new database state.
            store.runTranAsyncAndJoin { child.delete() }
        }

        transactional {
            assertThat(child.isRemoved).isTrue()
            assertQuery(owner.children).isEmpty()
        }
    }

    @Test
    fun `replay of a many-to-one assignment treats a one endpoint deleted concurrently as absent`() {
        val owner = transactional { Owner.new() }
        val child = transactional { Child.new() }

        transactional {
            child.owner = owner
            store.runTranAsyncAndJoin { owner.delete() }
        }

        transactional {
            assertThat(owner.isRemoved).isTrue()
            assertThat(child.owner).isNull()
        }
    }

    @Test
    fun `replay of a many-to-one reassignment to a concurrently deleted owner clears the previous owner`() {
        val previous = transactional { Owner.new() }
        val replacement = transactional { Owner.new() }
        val child = transactional { Child.new { owner = previous } }

        transactional {
            child.owner = replacement
            store.runTranAsyncAndJoin { replacement.delete() }
        }

        transactional {
            assertThat(child.owner).isNull()
            assertQuery(previous.children).isEmpty()
        }
    }

    @Test
    fun `replay of a many-to-one assignment with live endpoints keeps both the assignment and the concurrent write`() {
        val owner = transactional { Owner.new() }
        val child = transactional { Child.new() }

        transactional {
            child.owner = owner
            store.runTranAsyncAndJoin { owner.note = "winner" }
        }

        transactional {
            assertThat(child.owner).isEqualTo(owner)
            assertThat(owner.note).isEqualTo("winner")
            assertQuery(owner.children).containsExactly(child)
        }
    }

    // --- writes that involve a stale entity must fail before changing anything ---

    @Test
    fun `assigning a link to an entity deleted by another transaction throws EntityRemovedInDatabaseException`() {
        val live = transactional { Owner.new() }
        val gone = deletedOwner()
        val ticket = transactional { Ticket.new { reporter = live } }

        transactional {
            assertFailsWith<EntityRemovedInDatabaseException> { ticket.reporter = gone }
        }
    }

    @Test
    fun `a rejected link assignment to a deleted entity leaves the existing link untouched`() {
        val live = transactional { Owner.new() }
        val gone = deletedOwner()
        val ticket = transactional { Ticket.new { reporter = live } }

        transactional {
            runCatching { ticket.reporter = gone }
            assertThat(ticket.reporter).isEqualTo(live)
        }

        transactional {
            assertThat(ticket.reporter).isEqualTo(live)
        }
    }

    @Test
    fun `a rejected property write to a deleted entity does not fail a later replay of the transaction`() {
        val live = transactional { Owner.new { name = "initial" } }
        val gone = deletedOwner()

        transactional {
            assertFailsWith<EntityRemovedInDatabaseException> { gone.name = "ignored" }
            live.note = "loser"
            // Forces this transaction to be replayed; the rejected write must not be part of it.
            store.runTranAsyncAndJoin { live.name = "winner" }
        }

        transactional {
            assertThat(live.name).isEqualTo("winner")
            assertThat(live.note).isEqualTo("loser")
        }
    }

    // --- reads through a stale owner ---

    @Test
    fun `reading a one-to-many link of an entity deleted by another transaction throws EntityRemovedInDatabaseException`() {
        val gone = deletedOwner()

        transactional {
            assertFailsWith<EntityRemovedInDatabaseException> { gone.children.toList() }
        }
    }

    @Test
    fun `reading a many-to-many link of an entity deleted by another transaction throws EntityRemovedInDatabaseException`() {
        val gone = deletedOwner()

        transactional {
            assertFailsWith<EntityRemovedInDatabaseException> { gone.tags.toList() }
        }
    }
}
