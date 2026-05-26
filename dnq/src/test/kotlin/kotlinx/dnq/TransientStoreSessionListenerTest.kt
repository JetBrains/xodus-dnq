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

import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics
import jetbrains.exodus.database.*
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.isEmpty
import kotlinx.dnq.query.queryOf
import kotlinx.dnq.query.toList
import kotlinx.dnq.query.wrapAsQuery
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransientStoreSessionListenerTest : DBTest() {

    class LevelOneEntity(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<LevelOneEntity>()

        var name by xdRequiredStringProp()
    }

    class LevelTwoEntity(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<LevelTwoEntity>()

        var name by xdRequiredStringProp()
        var parent by xdLink0_1(LevelOneEntity, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    class LevelThreeEntity(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<LevelThreeEntity>()

        var name by xdRequiredStringProp()
        var parent by xdLink0_1(LevelOneEntity, onTargetDelete = OnDeletePolicy.CLEAR)
        val children by xdLink0_N(LevelTwoEntity, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    class ParentEntity(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ParentEntity>()

        var name by xdRequiredStringProp()
        var child by xdChild0_1(ChildEntity::parent)
    }

    class ChildEntity(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ChildEntity>()

        var name by xdRequiredStringProp()
        var parent: ParentEntity by xdParent(ParentEntity::child)
    }

    class TestUser(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TestUser>()

        var name by xdRequiredStringProp()
        val roles by xdChildren0_N(BelongsToUser::user)
    }

    class TestProject(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TestProject>()

        var name by xdRequiredStringProp();
    }

    abstract class BelongsToUser(entity: Entity): XdEntity(entity) {
        companion object : XdNaturalEntityType<BelongsToUser>()

        var user: TestUser by xdParent(TestUser::roles)
    }

    class TestProjectRole(entity: Entity) : BelongsToUser(entity) {
        companion object : XdNaturalEntityType<TestProjectRole>()

        var name by xdRequiredStringProp()
        var project by xdLink1(TestProject, onTargetDelete = OnDeletePolicy.CASCADE)
    }

    class JustCounter(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<JustCounter>()

        var value by xdRequiredIntProp()
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(
            LevelOneEntity, LevelTwoEntity, LevelThreeEntity,
            ParentEntity, ChildEntity, JustCounter,
            TestUser, TestProject, TestProjectRole
        )
    }

    @Test
    fun `listener should bring events about basic create-update-delete operations`() {

        val listener = RememberingListener()
        store.addListener(listener)

        val (entity1, entity2) = store.transactional {
            Pair(
                LevelOneEntity.new { name = "abc" },
                LevelOneEntity.new { name = "xyz" }
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
            val parent = LevelOneEntity.new { name = "parent1" }
            val child = LevelTwoEntity.new { name = "child"; this.parent = parent }

            Pair(parent, child)
        }
        assertEquals(setOf("parent1"), listener.addedLinks)
        assertTrue(listener.removedLinks.isEmpty())
        assertTrue(listener.deletedLinks.isEmpty())
        listener.clear()

        val parent2 = store.transactional {
            val newParent = LevelOneEntity.new { name = "parent2" }
            val tempParent = LevelOneEntity.new { name = "tempParent" }
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
    fun `XdEntityType#wrapAsQuery should work with removed entities in listeners`() {
        val listener = CallbackListener()
        store.addListener(listener)

        val entity = transactional { TestProject.new { name = "someEntity" } }

        listener.onFlush { changes ->
            assertEquals(1, changes.size)
            assertEquals(EntityChangeType.REMOVE, changes.first().changeType)

            val original = changes.first().transientEntity.toXd<TestProject>()
            val snapshot = changes.first().snapshotEntity.toXd<TestProject>()

            // they are removed already
            assertTrue(TestProject.queryOf(original).isEmpty)
            assertTrue(TestProject.queryOf(snapshot).isEmpty)

            // wrapAsQuery doesn't perform any queries
            assertEquals(
                listOf(snapshot),
                TestProject.wrapAsQuery(snapshot).toList()
            )
            assertEquals(
                listOf(original),
                TestProject.wrapAsQuery(original).toList()
            )
        }

        transactional {
            entity.delete()
        }
        listener.check()
    }

    @Test
    fun `listener should allow loading links from removed entities`() {
        val listener = CallbackListener()
        store.addListener(listener)

        var parent: LevelOneEntity? = null
        var child1: LevelTwoEntity? = null
        var child2: LevelTwoEntity? = null
        var grandchild: LevelThreeEntity? = null

        store.transactional {
            parent = LevelOneEntity.new { name = "parent1" }
            child1 = LevelTwoEntity.new { name = "child1"; this.parent = parent }
            child2 = LevelTwoEntity.new { name = "child2"; this.parent = parent }

            grandchild = LevelThreeEntity.new {
                name = "grandchild1"
                this.parent = parent
                children.add(child1)
                children.add(child2)
            }
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

            val childsFromGrandchild = grandchildSnapshot.getLinks("children").toList()
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
        listener.check()
    }

    @Test
    fun `listener should bring events from parent-child entities`() {
        val listener = CallbackListener()
        store.addListener(listener)

        val (parentEntity, childEntity) = store.transactional {
            val parentEntity = ParentEntity.new { name = "parentEntity" }
            val childEntity = ChildEntity.new { name = "childEntity"; parent = parentEntity }

            Pair(parentEntity, childEntity)
        }

        listener.onFlush { changes ->

            assertEquals(2, changes.size)
            changes.forEach { assertEquals(EntityChangeType.REMOVE, it.changeType) }
            val parentSnapshot = changes.find { it.snapshotEntity.id == parentEntity.entityId }!!
            val childSnapshot = changes.find { it.snapshotEntity.id == childEntity.entityId }!!

            assertEquals(
                childSnapshot.snapshotEntity,
                parentSnapshot.snapshotEntity.getLink("child")
            )
            assertEquals(
                parentSnapshot.snapshotEntity,
                childSnapshot.snapshotEntity.getLink("parent")
            )
        }

        store.transactional {
            parentEntity.delete()
        }
        listener.check()
    }

    @Test
    fun `listeners should work correctly with cascade deletions`() {
        val listener = CallbackListener()
        store.addListener(listener)

        val (user, project, role) = store.transactional {
            val user = TestUser.new { name = "user" }
            val project = TestProject.new { name = "project" }
            val role = TestProjectRole.new { name = "role"; this.user = user; this.project = project }

            Triple(user, project, role)
        }

        listener.onFlush { changes ->
            assertEquals(2, changes.size)
            val userChange = changes.find { it.snapshotEntity.id == user.entityId }!!
            assertEquals(EntityChangeType.REMOVE, userChange.changeType)
            val roleChange = changes.find { it.snapshotEntity.id == role.entityId }!!
            assertEquals(EntityChangeType.REMOVE, roleChange.changeType)

            val roleSnapshot = XdModel.toXd<TestProjectRole>(roleChange.snapshotEntity)

            assertEquals("user", roleSnapshot.user.name)
            assertEquals("project", roleSnapshot.project.name)
        }
        store.transactional {
            user.delete()
        }
        listener.check()
    }

    @Test
    fun `listener should work correctly with cascade deletions on transaction replay`() {
        val listener = CallbackListener()
        store.addListener(listener)

        val (parentEntity, childEntity, counter) = transactional {
            val parentEntity = ParentEntity.new { name = "parentEntity" }
            val childEntity = ChildEntity.new { name = "childEntity"; parent = parentEntity }
            val counter = JustCounter.new { value = 0 }

            Triple(parentEntity, childEntity, counter)
        }

        listener.onFlush { changes ->
            if (changes.any { it.changeType == EntityChangeType.REMOVE }) {
                val parentChange = changes.find { it.snapshotEntity.id == parentEntity.entityId }!!
                val childChange = changes.find { it.snapshotEntity.id == childEntity.entityId }!!

                assertEquals(EntityChangeType.REMOVE, parentChange.changeType)
                assertEquals(EntityChangeType.REMOVE, childChange.changeType)

                val parentSnapshot = XdModel.toXd<ParentEntity>(parentChange.snapshotEntity)
                val childSnapshot = XdModel.toXd<ChildEntity>(childChange.snapshotEntity)

                assertEquals(childSnapshot.parent, parentSnapshot)
                assertEquals(parentSnapshot.child, childSnapshot)
            }
        }

        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)

        val t1 = thread {
            transactional {
                counter.value += 1
                latch1.countDown()
                latch2.await()
            }
        }

        val t2 = thread {
            transactional {
                latch1.await()
                latch2.countDown()
                counter.value += 1
                parentEntity.delete()
            }
        }

        listOf(t1, t2).forEach { it.join() }
        listener.check(count = 2)
    }

    @Test
    fun `snapshot entity in flushed listener should return old link value for changed to-one link`() {
        val parent1 = store.transactional { LevelOneEntity.new { name = "parent1" } }
        val parent2 = store.transactional { LevelOneEntity.new { name = "parent2" } }
        val child = store.transactional { LevelTwoEntity.new { name = "child"; parent = parent1 } }

        val listener = CallbackListener()
        store.addListener(listener)

        listener.onFlush { changes ->
            val childChange = changes.singleOrNull {
                it.changeType == EntityChangeType.UPDATE && it.transientEntity.id == child.entityId
            } ?: error("expected UPDATE change for child")
            // Snapshot must reflect state before the transaction: parent should still be parent1
            assertEquals(parent1.entityId, childChange.snapshotEntity.getLink("parent")?.id)
        }

        store.transactional { child.parent = parent2 }
        listener.check()
    }

    @Test
    fun `snapshot entity in flushed listener should return old link value for cleared to-one link`() {
        val parent1 = store.transactional { LevelOneEntity.new { name = "parent1" } }
        val child = store.transactional { LevelTwoEntity.new { name = "child"; parent = parent1 } }

        val listener = CallbackListener()
        store.addListener(listener)

        listener.onFlush { changes ->
            val childChange = changes.singleOrNull {
                it.changeType == EntityChangeType.UPDATE && it.transientEntity.id == child.entityId
            } ?: error("expected UPDATE change for child")
            // Link was cleared to null; snapshot must still see the original parent1
            assertEquals(parent1.entityId, childChange.snapshotEntity.getLink("parent")?.id)
        }

        store.transactional { child.parent = null }
        listener.check()
    }

    @Test
    fun `snapshot entity in flushed listener should return null for to-one link set from null`() {
        val parent1 = store.transactional { LevelOneEntity.new { name = "parent1" } }
        val child = store.transactional { LevelTwoEntity.new { name = "child" } }

        val listener = CallbackListener()
        store.addListener(listener)

        listener.onFlush { changes ->
            val childChange = changes.singleOrNull {
                it.changeType == EntityChangeType.UPDATE && it.transientEntity.id == child.entityId
            } ?: error("expected UPDATE change for child")
            // Link was set from null; snapshot must report the original null value
            assertEquals(null, childChange.snapshotEntity.getLink("parent"))
        }

        store.transactional { child.parent = parent1 }
        listener.check()
    }

    @Test
    fun `snapshot of removed entity should preserve parent link cleared before delete`() {
        // A parent link cleared via setManyToOne(null, ...) before delete() in the
        // same transaction must still resolve to the original parent on the snapshot
        // of the removed entity. The removal snapshot used to capture link state
        // live at delete() time — after the in-txn mutation had already stripped
        // the edge — so a removedSyncBeforeConstraints listener observed null.
        val parent = store.transactional { ParentEntity.new { name = "p" } }
        val child = store.transactional { ChildEntity.new { name = "c"; this.parent = parent } }

        val listener = CallbackListener()
        store.addListener(listener)

        listener.onFlush { changes ->
            val childChange = changes.singleOrNull {
                it.changeType == EntityChangeType.REMOVE && it.transientEntity.id == child.entityId
            } ?: error("expected REMOVE change for child")
            // Snapshot must still see the original parent even though the parent link
            // was cleared before delete() in the same transaction.
            assertEquals(parent.entityId, childChange.snapshotEntity.getLink("parent")?.id)
        }

        store.transactional {
            AggregationAssociationSemantics.setManyToOne(
                /* parent = */ null,
                /* parentToChildLinkName = */ "child",
                /* childToParentLinkName = */ "parent",
                /* child = */ child.entity,
            )
            child.delete()
        }
        listener.check()
    }

    @Test
    fun `snapshot of removed entity should preserve to-many link cleared before delete`() {
        // Same regression as the to-one case, exercised on the plural snapshot reader.
        // A to-many link cleared in the same transaction as delete() must still report
        // its original members on snapshot.getLinks().
        val (parent, originalChildren) = store.transactional {
            val three = LevelThreeEntity.new { name = "three" }
            val twoA = LevelTwoEntity.new { name = "twoA" }
            val twoB = LevelTwoEntity.new { name = "twoB" }
            three.children.add(twoA)
            three.children.add(twoB)
            Pair(three, setOf(twoA.entityId, twoB.entityId))
        }

        val listener = CallbackListener()
        store.addListener(listener)

        listener.onFlush { changes ->
            val parentChange = changes.singleOrNull {
                it.changeType == EntityChangeType.REMOVE && it.transientEntity.id == parent.entityId
            } ?: error("expected REMOVE change for parent")
            val snapshotIds = parentChange.snapshotEntity.getLinks("children").map { it.id }.toSet()
            assertEquals(originalChildren, snapshotIds)
        }

        store.transactional {
            parent.children.clear()
            parent.delete()
        }
        listener.check()
    }

    @Test
    fun `snapshot of removed entity should reflect to-many link mixed add and remove before delete`() {
        // Pre-txn children: {A, B}. In the same txn we add C and remove A, then delete
        // the parent. The snapshot must roll back BOTH mutations: include A (was
        // removed in-txn) and exclude C (was added in-txn) — yielding the original {A, B}.
        val (parent, original, added) = store.transactional {
            val three = LevelThreeEntity.new { name = "three" }
            val twoA = LevelTwoEntity.new { name = "twoA" }
            val twoB = LevelTwoEntity.new { name = "twoB" }
            val twoC = LevelTwoEntity.new { name = "twoC" }
            three.children.add(twoA)
            three.children.add(twoB)
            Triple(three, setOf(twoA.entityId, twoB.entityId), twoC)
        }
        val originalA = store.transactional { parent.children.toList().first { it.name == "twoA" } }

        val listener = CallbackListener()
        store.addListener(listener)

        listener.onFlush { changes ->
            val parentChange = changes.singleOrNull {
                it.changeType == EntityChangeType.REMOVE && it.transientEntity.id == parent.entityId
            } ?: error("expected REMOVE change for parent")
            val snapshotIds = parentChange.snapshotEntity.getLinks("children").map { it.id }.toSet()
            assertEquals(original, snapshotIds)
        }

        store.transactional {
            parent.children.add(added)
            parent.children.remove(originalA)
            parent.delete()
        }
        listener.check()
    }

    @Test
    fun `snapshot of removed entity should exclude to-one link added in same transaction`() {
        // Pre-txn the child has no parent. In the same txn we set parent and then
        // delete the child. The snapshot must reflect the pre-txn null — the link
        // edge added in-txn must be subtracted from the snapshot's link state.
        val child = store.transactional { LevelTwoEntity.new { name = "orphan" } }

        val listener = CallbackListener()
        store.addListener(listener)

        listener.onFlush { changes ->
            val childChange = changes.singleOrNull {
                it.changeType == EntityChangeType.REMOVE && it.transientEntity.id == child.entityId
            } ?: error("expected REMOVE change for child")
            assertEquals(null, childChange.snapshotEntity.getLink("parent"))
        }

        store.transactional {
            child.parent = LevelOneEntity.new { name = "newParent" }
            child.delete()
        }
        listener.check()
    }
}

class CallbackListener : TransientStoreSessionListener {

    var callback: (Set<TransientEntityChange>) -> Unit = { _ -> }
    val callbackErrors: MutableList<Throwable> = mutableListOf()
    val callCount = AtomicInteger(0)

    fun onFlush(callback: (Set<TransientEntityChange>) -> Unit) {
        callbackErrors.clear()
        callCount.set(0)
        this.callback = { it: Set<TransientEntityChange> ->
            try {
                callback(it)
            } catch (e: Throwable) {
                callbackErrors.add(e)
            }
            callCount.incrementAndGet()
        }
    }

    fun check(count: Int = 1) {
        assertEquals(count, callCount.get())
        if (callbackErrors.size == 1) {
            throw callbackErrors[0]
        } else if (callbackErrors.isNotEmpty()) {
            throw AssertionError("More than one error: ${callbackErrors.joinToString("\n")}", callbackErrors[0])
        }
    }

    override fun flushed(session: TransientStoreSession, changedEntities: Set<TransientEntityChange>) =
        callback(changedEntities)

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
