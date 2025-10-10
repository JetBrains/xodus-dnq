/**
 * Copyright 2006 - 2025 JetBrains s.r.o.
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

import jetbrains.exodus.database.EntityChangeType
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.database.TransientStoreSessionListener
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.link.OnDeletePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransientStoreSessionListenerTest : DBTest() {

    class SimpleEntity(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<SimpleEntity>()

        var name by xdRequiredStringProp()
    }

    class EntityWithLink(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<EntityWithLink>()

        var name by xdRequiredStringProp()
        var link by xdLink0_1(SimpleEntity, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(SimpleEntity, EntityWithLink)
    }

    @Test
    fun `listener should bring events about basic create-update-delete operations`() {

        val listener = Listener()
        store.addListener(listener)

        val (entity1, entity2) = store.transactional {
            Pair(
                SimpleEntity.new { name = "abc" },
                SimpleEntity.new { name = "xyz" }
            )
        }

        assertEquals(setOf("abc", "xyz"), listener.added)
        assertTrue(listener.removed.isEmpty())
        assertTrue(listener.updated.isEmpty())
        listener.clear()

        store.transactional {
            entity1.name = "000"
            entity1.name = "123"
        }
        assertEquals(mapOf("abc" to "123"), listener.updated)
        assertTrue(listener.removed.isEmpty())
        assertTrue(listener.added.isEmpty())
        listener.clear()

        store.transactional {
            // entity2.name = "000"
            // this looks not right. if we update a record and then delete it, the listener will get
            // a snapshot of the record's state before the deletion. would be great if it returned
            // the state at transaction start
            entity2.delete()
        }
        assertEquals(setOf("xyz"), listener.removed)
        assertTrue(listener.updated.isEmpty())
        assertTrue(listener.added.isEmpty())
        listener.clear()
    }

    @Test
    fun `listener should bring events about changes in links`() {

        val listener = Listener()
        store.addListener(listener)

        val (parent, child) = store.transactional {
            val parent = SimpleEntity.new { name = "parent1" }
            val child = EntityWithLink.new { name = "child"; link = parent }

            Pair(parent, child)
        }
        assertEquals(setOf("parent1"), listener.addedLinks)
        assertTrue(listener.removedLinks.isEmpty())
        assertTrue(listener.deletedLinks.isEmpty())
        listener.clear()

        val parent2 = store.transactional {
            val newParent = SimpleEntity.new { name = "parent2" }
            val tempParent = SimpleEntity.new { name = "tempParent" }
            child.link = tempParent
            child.link = newParent
            newParent
        }
        assertEquals(setOf("parent1"), listener.removedLinks)
        assertEquals(setOf("parent2"), listener.addedLinks)
        assertTrue(listener.deletedLinks.isEmpty())
        listener.clear()

        store.transactional {
            // parent2.name = "someNewName"
            // again, this doesn't work as expected. we don't see the state of the entity at the start of transaction
            parent2.delete()
        }
        assertEquals(setOf("parent2"), listener.deletedLinks)
        assertTrue(listener.addedLinks.isEmpty())
        assertTrue(listener.removedLinks.isEmpty())
        listener.clear()
    }

    class Listener : TransientStoreSessionListener {
        val added = mutableSetOf<String>()
        val removed = mutableSetOf<String>()
        val updated = mutableMapOf<String, String>()

        val addedLinks = mutableSetOf<String>()
        val removedLinks = mutableSetOf<String>()
        val deletedLinks = mutableSetOf<String>()

        fun clear() {
            added.clear()
            removed.clear()
            updated.clear()
            addedLinks.clear()
            removedLinks.clear()
            deletedLinks.clear()
        }

        override fun flushed(
            session: TransientStoreSession,
            changedEntities: @JvmSuppressWildcards Set<TransientEntityChange>
        ) {
            for (change in changedEntities) {
                when (change.changeType) {
                    EntityChangeType.ADD -> added.add(change.transientEntity.getProperty("name") as String)
                    EntityChangeType.REMOVE -> removed.add(change.snapshotEntity.getProperty("name") as String)
                    EntityChangeType.UPDATE -> updated[change.snapshotEntity.getProperty("name") as String] =
                        change.transientEntity.getProperty("name") as String
                }

                change.changedLinksDetailed?.let { changes ->
                    changes.forEach { (_, change) ->
                        change.addedEntities?.forEach {
                            addedLinks.add(it.getProperty("name") as String)
                        }

                        change.removedEntities?.forEach {
                            removedLinks.add(it.getProperty("name") as String)
                        }

                        change.deletedEntitiesSnapshots?.forEach {
                            deletedLinks.add(it.getProperty("name") as String)
                        }
                    }
                }

            }
        }

        override fun beforeFlushBeforeConstraints(
            session: TransientStoreSession,
            changedEntities: @JvmSuppressWildcards Set<TransientEntityChange>
        ) {
        }

        override fun afterConstraintsFail(
            session: TransientStoreSession,
            exceptions: @JvmSuppressWildcards Set<DataIntegrityViolationException>
        ) {
        }
    }
}