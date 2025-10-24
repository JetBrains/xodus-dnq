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

import jetbrains.exodus.database.*
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.link.OnDeletePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransientStoreSessionListenerTest : DBTest() {

    class Parent(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Parent>()

        var name by xdRequiredStringProp()
    }

    class Child(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Child>()

        var name by xdRequiredStringProp()
        var parent by xdLink0_1(Parent, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    class Grandchild(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Grandchild>()

        var name by xdRequiredStringProp()
        var parent by xdLink0_1(Parent, onTargetDelete = OnDeletePolicy.CLEAR)
        val childs by xdLink0_N(Child, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(Parent, Child, Grandchild)
    }

    @Test
    fun `listener should bring events about basic create-update-delete operations`() {

        val listener = RememberingListener()
        store.addListener(listener)

        val (entity1, entity2) = store.transactional {
            Pair(
                Parent.new { name = "abc" },
                Parent.new { name = "xyz" }
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

        val listener = RememberingListener()
        store.addListener(listener)

        val (parent, child) = store.transactional {
            val parent = Parent.new { name = "parent1" }
            val child = Child.new { name = "child"; this.parent = parent }

            Pair(parent, child)
        }
        assertEquals(setOf("parent1"), listener.addedLinks)
        assertTrue(listener.removedLinks.isEmpty())
        assertTrue(listener.deletedLinks.isEmpty())
        listener.clear()

        val parent2 = store.transactional {
            val newParent = Parent.new { name = "parent2" }
            val tempParent = Parent.new { name = "tempParent" }
            child.parent = tempParent
            child.parent = newParent
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

    @Test
    fun `listener should allow loading links from removed entities`() {
        val listener = CallbackListener()
        store.addListener(listener)

        var parent: Parent? = null
        var child1: Child? = null
        var child2: Child? = null
        var grandchild: Grandchild? = null

        store.transactional {
            parent = Parent.new { name = "parent1" }
            child1 = Child.new { name = "child1"; this.parent = parent }
            child2 = Child.new { name = "child2"; this.parent = parent }

            grandchild = Grandchild.new {
                name = "grandchild1"
                this.parent = parent
                childs.add(child1)
                childs.add(child2)
            }
        }

        store.transactional {
            val ll = child1?.parent

            println(ll)
        }

        listener.onFlush { changes ->
            assertEquals(2, changes.size)
            val child1change = changes.find { it.transientEntity.id == child1!!.entityId }!!
            val grandchildChange = changes.find { it.transientEntity.id == grandchild!!.entityId }!!

            val childSnapshot = child1change.snapshotEntity
            assertTrue(childSnapshot.isRemoved)
            val grandchildSnapshot = grandchildChange.snapshotEntity
            assertTrue(grandchildSnapshot.isRemoved)

            assertEquals("child1", childSnapshot.getProperty("name"))
            assertEquals("grandchild1", grandchildSnapshot.getProperty("name"))

            val parentFromChild = childSnapshot.getLink("parent")
            assertFalse((parentFromChild as TransientEntity).isRemoved)

            val parentFromGrandchild = grandchildSnapshot.getLink("parent")
            assertFalse((parentFromGrandchild as TransientEntity).isRemoved)

            val childsFromGrandchild = grandchildSnapshot.getLinks("childs").toList()
            assertEquals(2, childsFromGrandchild.size)

            val child1Linked = childsFromGrandchild.find { it.id == child1!!.entityId }
            assertTrue((child1Linked as TransientEntity).isRemoved)
            assertEquals("child1", child1Linked.getProperty("name"))
            val child2Linked = childsFromGrandchild.find { it.id == child2!!.entityId }
            assertFalse((child2Linked as TransientEntity).isRemoved)
            assertEquals("child2", child2Linked.getProperty("name"))

            // accessing parent again:
            val parentAgain = child1Linked.getLink("parent")
            assertFalse((parentAgain as TransientEntity).isRemoved)
            val parentAgain2 = child2Linked.getLink("parent")
            assertFalse((parentAgain2 as TransientEntity).isRemoved)
        }

        store.transactional {
            grandchild?.delete()
            child1?.delete()
        }
        listener.checkError()
    }
}

class CallbackListener : TransientStoreSessionListener {

    var callback: (Set<TransientEntityChange>) -> Unit = { _ -> }
    var callbackError: Throwable? = null

    fun onFlush(callback: (Set<TransientEntityChange>) -> Unit) {
        callbackError = null
        this.callback = { it: Set<TransientEntityChange> ->
            try {
                callback(it)
            } catch (e: Throwable) {
                callbackError = e
            }
            this.callback = { _ -> }
        }
    }

    fun checkError() {
        callbackError?.let { throw it }
    }

    override fun flushed(
        session: TransientStoreSession,
        changedEntities: Set<TransientEntityChange>
    ) {
        callback(changedEntities)
    }

    override fun beforeFlushBeforeConstraints(
        session: TransientStoreSession,
        changedEntities: Set<TransientEntityChange>
    ) {
    }

    override fun afterConstraintsFail(
        session: TransientStoreSession,
        exceptions: Set<DataIntegrityViolationException>
    ) {
    }
}

class RememberingListener : TransientStoreSessionListener {
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
        changedEntities: Set<TransientEntityChange>
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
        changedEntities: Set<TransientEntityChange>
    ) {
    }

    override fun afterConstraintsFail(
        session: TransientStoreSession,
        exceptions: @JvmSuppressWildcards Set<DataIntegrityViolationException>
    ) {
    }
}
