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
package jetbrains.exodus.entitystore.youtrackdb

import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId
import jetbrains.exodus.ExodusException
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.PersistentEntityId
import jetbrains.exodus.entitystore.util.EntityIdSetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Guard test for the `EntityId` equality/hash contract across its two implementations:
 *  - [RIDEntityId] (dnq-entity-store): a *resolved* id carrying a physical RID;
 *  - [PersistentEntityId] (dnq-xodus-open-api): a logical `(typeId, localId)` id, not yet looked up —
 *    also the type materialized by `EntityIdSet` iteration and returned by `toEntityId`.
 *
 * There is no shared base class: each impl reproduces the `(typeId, localId)` identity
 * (`equals`/`hashCode`/`compareTo`/`toString`) on its own — `RIDEntityId` because the contract is
 * tiny, and `PersistentEntityId` because it must keep the exact field layout of the classic Xodus
 * class for Java-serialization compatibility (see its javadoc) and so cannot inherit fields from a
 * base. This test pins the invariant that an `EntityId` is identified by `(typeId, localId)` alone,
 * so the impls stay interchangeable in `equals`, `hashCode`, `compareTo`, and hash-based
 * collections. If either copy's formula drifts from the other, this fails loudly.
 *
 * It also includes a deliberately *foreign* [EntityId] ([ForeignEntityId]) standing in for an id
 * from yet another implementation: it proves each impl really compares through the `EntityId`
 * interface rather than by concrete type — exactly the scenario that breaks when ids built two
 * different ways are compared for the same entity.
 */
class EntityIdContractTest {

    /**
     * A deliberately foreign [EntityId] — a third, throwaway implementation standing in for an id
     * coming from somewhere else. It honors the documented `(typeId, localId)` identity and the
     * `(typeId << 20) xor localId` hash formula, so it must be fully interchangeable with the two
     * production ids.
     */
    private class ForeignEntityId(private val t: Int, private val l: Long) : EntityId {
        override fun getTypeId(): Int = t
        override fun getLocalId(): Long = l
        override fun toString(): String = "$t-$l"
        override fun hashCode(): Int = ((t shl 20).toLong() xor l).toInt()
        override fun equals(other: Any?): Boolean =
            other is EntityId && t == other.typeId && l == other.localId

        override fun compareTo(other: EntityId): Int =
            if (t != other.typeId) t.compareTo(other.typeId) else l.compareTo(other.localId)
    }

    /** A resolved-shaped [RIDEntityId] built without a DB (throwaway RID part; equality ignores the RID). */
    private fun ridEntityId(typeId: Int, localId: Long): EntityId =
        RIDEntityId(typeId, localId, ChangeableRecordId(), null)

    /** Pull the lightweight id out of an `EntityIdSet` the way production iteration does. */
    private fun iteratedId(typeId: Int, localId: Long): EntityId =
        EntityIdSetFactory.newSet().add(typeId, localId).iterator().next()

    /** All `EntityId` implementations for the same logical id, which must be mutually equal. */
    private fun allImpls(typeId: Int, localId: Long): List<EntityId> = listOf(
        ridEntityId(typeId, localId),
        PersistentEntityId(typeId, localId),
        iteratedId(typeId, localId),
        ForeignEntityId(typeId, localId),
    )

    @Test
    fun `all impls are mutually equal and hash-consistent for the same typeId and localId`() {
        listOf(3 to 100L, 0 to 0L, 12 to 58041L, Int.MAX_VALUE to Long.MAX_VALUE)
            .forEach { (typeId, localId) ->
                val impls = allImpls(typeId, localId)
                for (a in impls) for (b in impls) {
                    assertEquals("$typeId-$localId: ${a.javaClass.simpleName}.equals(${b.javaClass.simpleName})", a, b)
                    assertEquals(
                        "$typeId-$localId: hashCode ${a.javaClass.simpleName} vs ${b.javaClass.simpleName}",
                        a.hashCode(),
                        b.hashCode()
                    )
                    assertEquals(a.toString(), b.toString())
                }
            }
    }

    @Test
    fun `EntityIdSet iteration yields an PersistentEntityId`() {
        val iterated = iteratedId(5, 99L)
        assertTrue(
            "EntityIdSet must materialize PersistentEntityId, got ${iterated.javaClass.name}",
            iterated is PersistentEntityId
        )
    }

    @Test
    fun `the impls are interchangeable as keys in hash-based collections`() {
        val impls = allImpls(7, 42L)
        for (a in impls) {
            assertTrue(
                "HashSet<${a.javaClass.simpleName}> must contain every other impl",
                impls.all { hashSetOf(a).contains(it) }
            )
        }
        // all representations of the same id collapse to a single element
        assertEquals(1, impls.toHashSet().size)
    }

    @Test
    fun `different ids are not equal across impls`() {
        val rid = ridEntityId(7, 42L)
        assertNotEquals(rid, PersistentEntityId(7, 43L)) // different localId
        assertNotEquals(rid, PersistentEntityId(8, 42L)) // different typeId
    }

    @Test
    fun `compareTo is consistent with equals and orders by typeId then localId across impls`() {
        // Equal ids must compare as 0 in every cross-impl direction (consistency with equals).
        val impls = allImpls(7, 42L)
        for (a in impls) for (b in impls) {
            assertEquals(
                "${a.javaClass.simpleName}.compareTo(${b.javaClass.simpleName}) for equal ids",
                0,
                a.compareTo(b)
            )
        }

        // Ordering is by typeId first, then localId — checked by sign, cross-class, and sign-symmetric.
        val base = ridEntityId(7, 42L)
        assertTrue(base.compareTo(PersistentEntityId(7, 43L)) < 0) // same type, larger localId
        assertTrue(base.compareTo(PersistentEntityId(7, 41L)) > 0) // same type, smaller localId
        assertTrue(base.compareTo(PersistentEntityId(8, 42L)) < 0) // larger type dominates localId
        assertTrue(base.compareTo(PersistentEntityId(6, 42L)) > 0) // smaller type dominates localId
        assertTrue(PersistentEntityId(7, 43L).compareTo(base) > 0) // reverse direction flips sign
        assertTrue(PersistentEntityId(8, 42L).compareTo(base) > 0) // 8 > 7 regardless of which side
    }

    @Test
    fun `negative typeId or localId is rejected at construction across all impls`() {
        assertFailsWith<ExodusException> { PersistentEntityId(-1, 0L) }
        assertFailsWith<ExodusException> { PersistentEntityId(0, -1L) }
        assertFailsWith<ExodusException> { RIDEntityId(-1, 0L, ChangeableRecordId(), null) }
        assertFailsWith<ExodusException> { RIDEntityId(0, -1L, ChangeableRecordId(), null) }
    }
}
