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

import jetbrains.exodus.entitystore.youtrackdb.testutil.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

class YTDBStoreTransactionPolymorphicTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB(initializeIssueSchema = false)

    override val youTrackDb = orientDbRule

    private fun givenUserHierarchy() {
        youTrackDb.withSession { session ->
            val baseClass = session.getOrCreateVertexClass(BaseUser.CLASS)
            listOf(
                session.getOrCreateVertexClass(User.CLASS),
                session.getOrCreateVertexClass(Guest.CLASS)
            ).forEach { it.addSuperClass(baseClass) }
        }
        withStoreTx { tx ->
            tx.createUser(BaseUser.CLASS, "base1").also {
                it.setProperty("age", 30)
            }
            tx.createUser(User.CLASS, "user1").also {
                it.setProperty("age", 25)
            }
            tx.createUser(Guest.CLASS, "guest1").also {
                it.setProperty("age", 20)
            }
        }
    }

    @Test
    fun `non-polymorphic getAll returns exact type only`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = tx.getAll(BaseUser.CLASS, polymorphic = false)
            assertNamesExactly(result, "base1")
        }
    }

    @Test
    fun `default getAll returns all subtypes`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = tx.getAll(BaseUser.CLASS)
            assertNamesExactly(result, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic find by property returns exact type only`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            // find with exact value
            val result = tx.find(BaseUser.CLASS, "age", 30, polymorphic = false)
            assertNamesExactly(result, "base1")

            // age=25 belongs to User subtype — non-polymorphic must exclude it
            val subtypeValue = tx.find(BaseUser.CLASS, "age", 25, polymorphic = false)
            assertNamesExactly(subtypeValue)

            // Polymorphic find matches across subtypes
            val polyResult = tx.find(BaseUser.CLASS, "age", 25)
            assertNamesExactly(polyResult, "user1")
        }
    }

    @Test
    fun `non-polymorphic find range returns exact type only`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = tx.find(BaseUser.CLASS, "age", 0, 100, polymorphic = false)
            assertNamesExactly(result, "base1")

            val polyResult = tx.find(BaseUser.CLASS, "age", 0, 100)
            assertNamesExactly(polyResult, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic findWithProp returns exact type only`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = tx.findWithProp(BaseUser.CLASS, "name", polymorphic = false)
            assertNamesExactly(result, "base1")
        }
    }

    @Test
    fun `default findWithProp returns all subtypes`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = tx.findWithProp(BaseUser.CLASS, "name")
            assertNamesExactly(result, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic findContaining returns exact type only`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            // "1" matches all entity names — non-polymorphic should still return only BaseUser
            val result = tx.findContaining(
                BaseUser.CLASS, "name", "1", false, polymorphic = false
            )
            assertNamesExactly(result, "base1")

            val polyResult = tx.findContaining(BaseUser.CLASS, "name", "1", false)
            assertNamesExactly(polyResult, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic findStartingWith returns exact type only`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            // Empty prefix matches all names — non-polymorphic should still return only BaseUser
            val result = tx.findStartingWith(BaseUser.CLASS, "name", "", polymorphic = false)
            assertNamesExactly(result, "base1")

            val polyResult = tx.findStartingWith(BaseUser.CLASS, "name", "")
            assertNamesExactly(polyResult, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic findWithBlob delegates to findWithProp`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = tx.findWithBlob(BaseUser.CLASS, "name", polymorphic = false)
            assertNamesExactly(result, "base1")

            val polyResult = tx.findWithBlob(BaseUser.CLASS, "name")
            assertNamesExactly(polyResult, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic findWithLinks returns exact type only`() {
        youTrackDb.withSession { session ->
            val baseClass = session.getOrCreateVertexClass(BaseUser.CLASS)
            listOf(
                session.getOrCreateVertexClass(User.CLASS),
                session.getOrCreateVertexClass(Guest.CLASS)
            ).forEach { it.addSuperClass(baseClass) }
            session.getOrCreateVertexClass("Target")
        }
        withStoreTx { tx ->
            val base = tx.createUser(BaseUser.CLASS, "base1")
            val user = tx.createUser(User.CLASS, "user1")
            val target = tx.newEntity("Target")
            base.addLink("friend", target)
            user.addLink("friend", target)
        }

        withStoreTx { tx ->
            val result = tx.findWithLinks(BaseUser.CLASS, "friend", polymorphic = false)
            assertNamesExactly(result, "base1")

            val polyResult = tx.findWithLinks(BaseUser.CLASS, "friend")
            assertNamesExactly(polyResult, "base1", "user1")
        }
    }

    @Test
    fun `non-polymorphic findIds returns exact type only`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = tx.findIds(
                BaseUser.CLASS, Long.MIN_VALUE, Long.MAX_VALUE, polymorphic = false
            )
            assertNamesExactly(result, "base1")

            val polyResult = tx.findIds(BaseUser.CLASS, Long.MIN_VALUE, Long.MAX_VALUE)
            assertNamesExactly(polyResult, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic findWithLinks 4-arg returns exact type only`() {
        youTrackDb.withSession { session ->
            val baseClass = session.getOrCreateVertexClass(BaseUser.CLASS)
            listOf(
                session.getOrCreateVertexClass(User.CLASS),
                session.getOrCreateVertexClass(Guest.CLASS)
            ).forEach { it.addSuperClass(baseClass) }
            session.getOrCreateVertexClass("Target")
        }
        withStoreTx { tx ->
            val base = tx.createUser(BaseUser.CLASS, "base1")
            val user = tx.createUser(User.CLASS, "user1")
            val target = tx.newEntity("Target")
            base.addLink("friend", target)
            user.addLink("friend", target)
            target.addLink("friendOf", base)
            target.addLink("friendOf", user)
        }

        withStoreTx { tx ->
            val result = tx.findWithLinks(
                BaseUser.CLASS, "friend", "Target", "friendOf",
                polymorphic = false
            )
            assertNamesExactly(result, "base1")

            val polyResult = tx.findWithLinks(
                BaseUser.CLASS, "friend", "Target", "friendOf"
            )
            assertNamesExactly(polyResult, "base1", "user1")
        }
    }

    // --- Step 2: query()-based and direct constructor methods ---

    @Test
    fun `non-polymorphic findLinks by entityId returns exact type only`() {
        givenUserHierarchyWithLinks()

        withStoreTx { tx ->
            val target = tx.find(BaseUser.CLASS, "name", "base1").first()
            val targetId = target.id as YTDBEntityId

            val result = tx.findLinks(
                BaseUser.CLASS, targetId, "friend", polymorphic = false
            )
            assertNamesExactly(result, "base1")

            val polyResult = tx.findLinks(BaseUser.CLASS, targetId, "friend")
            assertNamesExactly(polyResult, "base1", "user1")
        }
    }

    @Test
    fun `non-polymorphic findLinks by entity returns exact type only`() {
        givenUserHierarchyWithLinks()

        withStoreTx { tx ->
            val target = tx.find(BaseUser.CLASS, "name", "base1").first()

            val result = tx.findLinks(
                BaseUser.CLASS, target, "friend", polymorphic = false
            )
            assertNamesExactly(result, "base1")

            val polyResult = tx.findLinks(BaseUser.CLASS, target, "friend")
            assertNamesExactly(polyResult, "base1", "user1")
        }
    }

    @Test
    fun `non-polymorphic sort returns exact type only sorted`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = tx.sort(
                BaseUser.CLASS, "age", true, polymorphic = false
            )
            assertNamesExactlyInOrder(result, "base1")

            val polyResult = tx.sort(BaseUser.CLASS, "age", true)
            assertNamesExactlyInOrder(polyResult, "guest1", "user1", "base1")
        }
    }

    @Test
    fun `non-polymorphic sort with rightOrder uses method polymorphic parameter`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            // rightOrder contains all BaseUser subtypes (polymorphic=true)
            val rightOrder = tx.getAll(BaseUser.CLASS)

            // But the sort method's own polymorphic=false should control the output
            val result = tx.sort(
                BaseUser.CLASS, "age", rightOrder, true, polymorphic = false
            )
            assertNamesExactlyInOrder(result, "base1")

            val polyResult = tx.sort(BaseUser.CLASS, "age", rightOrder, true)
            assertNamesExactlyInOrder(polyResult, "guest1", "user1", "base1")
        }
    }

    @Test
    fun `non-polymorphic findWithPropSortedByValue returns exact type only`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = tx.findWithPropSortedByValue(
                BaseUser.CLASS, "name", polymorphic = false
            )
            assertNamesExactlyInOrder(result, "base1")

            val polyResult = tx.findWithPropSortedByValue(BaseUser.CLASS, "name")
            assertNamesExactlyInOrder(polyResult, "base1", "guest1", "user1")
        }
    }

    @Test
    fun `non-polymorphic findLinks by entities returns exact type only`() {
        givenUserHierarchyWithLinks()

        withStoreTx { tx ->
            val targets = tx.find(BaseUser.CLASS, "name", "base1")

            val result = tx.findLinks(
                BaseUser.CLASS, targets, "friend", polymorphic = false
            )
            assertNamesExactly(result, "base1")

            val polyResult = tx.findLinks(BaseUser.CLASS, targets, "friend")
            assertNamesExactly(polyResult, "base1", "user1")
        }
    }

    @Test
    fun `findLinksUntyped polymorphic flag does not affect results (no HasLabel)`() {
        givenUserHierarchyWithLinks()

        withStoreTx { tx ->
            val target = tx.find(BaseUser.CLASS, "name", "base1").first()

            // findLinksUntyped has no HasLabel, so both flags produce identical results.
            // The flag exists for API consistency and downstream combination validation.
            val nonPoly = tx.findLinksUntyped(target, "friend", polymorphic = false)
            assertNamesExactly(nonPoly, "base1", "user1")

            val poly = tx.findLinksUntyped(target, "friend")
            assertNamesExactly(poly, "base1", "user1")
        }
    }

    @Test
    fun `non-polymorphic sortLinks returns exact type only`() {
        givenUserHierarchyWithLinks()

        withStoreTx { tx ->
            // sortedLinks: targets of the "friend" link (base1)
            val sortedLinks = tx.find(BaseUser.CLASS, "name", "base1")
            val rightOrder = tx.getAll(BaseUser.CLASS)

            // InLink("friend") from base1 → [base1, user1], intersect with rightOrder,
            // HasLabel(BaseUser, polymorphic=false) → base1 only
            val result = tx.sortLinks(
                BaseUser.CLASS, sortedLinks, false, "friend", rightOrder,
                polymorphic = false
            )
            assertNamesExactly(result, "base1")

            val polyResult = tx.sortLinks(
                BaseUser.CLASS, sortedLinks, false, "friend", rightOrder
            )
            assertNamesExactly(polyResult, "base1", "user1")
        }
    }

    @Test
    fun `non-polymorphic sortLinks 7-arg returns exact type only`() {
        givenUserHierarchyWithLinks()

        withStoreTx { tx ->
            val sortedLinks = tx.find(BaseUser.CLASS, "name", "base1")
            val rightOrder = tx.getAll(BaseUser.CLASS)

            val result = tx.sortLinks(
                BaseUser.CLASS, sortedLinks, false, "friend", rightOrder,
                BaseUser.CLASS, "friend", polymorphic = false
            )
            assertNamesExactly(result, "base1")

            val polyResult = tx.sortLinks(
                BaseUser.CLASS, sortedLinks, false, "friend", rightOrder,
                BaseUser.CLASS, "friend"
            )
            assertNamesExactly(polyResult, "base1", "user1")
        }
    }

    // --- Combination of tx-level results ---

    @Test
    fun `non-polymorphic getAll union across types returns exact-type instances`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyBase = tx.getAll(BaseUser.CLASS, polymorphic = false)
            val nonPolyUser = tx.getAll(User.CLASS, polymorphic = false)

            // All ∪ All with different labels → UnionAll (Track 5 path)
            // Non-poly: [base1] ∪ [user1] = [base1, user1]
            // Poly leak on BaseUser would add user1+guest1
            val result = nonPolyBase.union(nonPolyUser)
            assertNamesExactly(result, "base1", "user1")
        }
    }

    @Test
    fun `non-polymorphic getAll intersect find returns exact-type instances`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val allBase = tx.getAll(BaseUser.CLASS, polymorphic = false)
            val findBase = tx.find(BaseUser.CLASS, "age", 30, polymorphic = false)

            // All ∩ PropEqual, same label → fused Where
            // Non-poly: [base1] ∩ [base1(age=30)] = [base1]
            val result = allBase.intersect(findBase)
            assertNamesExactly(result, "base1")

            // Poly leak: getAll(BaseUser) would return [base1,user1,guest1],
            // find(BaseUser,age=30) would also match base1(30) only → [base1]
            // Not falsifiable on intersect alone, so also verify the union:
            val polyBase = tx.getAll(BaseUser.CLASS)
            val polyFind = tx.find(BaseUser.CLASS, "age", 25)
            // Poly: [base1,user1,guest1] ∩ [user1(age=25)] = [user1]
            assertNamesExactly(polyBase.intersect(polyFind), "user1")
            // Non-poly: [base1] ∩ [](age=25 belongs to User) = []
            val nonPolyFind25 = tx.find(BaseUser.CLASS, "age", 25, polymorphic = false)
            assertNamesExactly(allBase.intersect(nonPolyFind25))
        }
    }

    @Test
    fun `non-polymorphic find minus getAll cross-type returns correct result`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            // find(BaseUser, age in [0,100], non-poly) → [base1]
            val findRange = tx.find(BaseUser.CLASS, "age", 0, 100, polymorphic = false)
            // getAll(User, non-poly) → [user1]
            val allUser = tx.getAll(User.CLASS, polymorphic = false)

            // [base1] \ [user1] = [base1] (disjoint)
            // Poly leak on findRange: [base1,user1,guest1] \ [user1] = [base1,guest1]
            val result = findRange.minus(allUser)
            assertNamesExactly(result, "base1")
        }
    }

    @Test
    fun `non-polymorphic getAll concat returns exact-type instances in order`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyBase = tx.getAll(BaseUser.CLASS, polymorphic = false)
            val nonPolyGuest = tx.getAll(Guest.CLASS, polymorphic = false)

            // [base1] ++ [guest1] = [base1, guest1]
            // Poly leak on BaseUser: [base1,user1,guest1] ++ [guest1] = 4 results
            val result = nonPolyBase.concat(nonPolyGuest)
            assertNamesExactlyInOrder(result, "base1", "guest1")
        }
    }

    @Test
    fun `mixing polymorphic and non-polymorphic tx results throws on combination`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = tx.getAll(BaseUser.CLASS)
            val nonPoly = tx.getAll(BaseUser.CLASS, polymorphic = false)

            assertFailsWith<IllegalArgumentException> { poly.union(nonPoly) }
            assertFailsWith<IllegalArgumentException> { nonPoly.intersect(poly) }
            assertFailsWith<IllegalArgumentException> { poly.minus(nonPoly) }
            assertFailsWith<IllegalArgumentException> { nonPoly.concat(poly) }
        }
    }

    private fun givenUserHierarchyWithLinks() {
        youTrackDb.withSession { session ->
            val baseClass = session.getOrCreateVertexClass(BaseUser.CLASS)
            listOf(
                session.getOrCreateVertexClass(User.CLASS),
                session.getOrCreateVertexClass(Guest.CLASS)
            ).forEach { it.addSuperClass(baseClass) }
        }
        withStoreTx { tx ->
            val base = tx.createUser(BaseUser.CLASS, "base1")
            val user = tx.createUser(User.CLASS, "user1")
            tx.createUser(Guest.CLASS, "guest1")
            // Both base and user link to base1 (self-referencing for base, cross-type for user)
            base.addLink("friend", base)
            user.addLink("friend", base)
        }
    }
}
