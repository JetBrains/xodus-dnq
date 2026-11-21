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
package jetbrains.exodus.entitystore.youtrackdb.iterate

import jetbrains.exodus.entitystore.youtrackdb.getOrCreateVertexClass
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityId
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.testutil.*
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YTDBPolymorphicQueryTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB(initializeIssueSchema = false)

    override val youTrackDb = orientDbRule

    @Test
    fun `typed exclusion provider parity prerequisite`() {
        givenUserHierarchy()
        withStoreTx { tx ->
            val entities = tx.getAll(BaseUser.CLASS).associateBy { it.getProperty("name").toString() }
            val base = GremlinQuery.Labeled(GremlinQuery.all, BaseUser.CLASS)
                .then(GremlinBlock.Sort(GremlinBlock.Sort.ByProp("name"), GremlinBlock.SortDirection.ASC))
            for (poly in listOf(true, false)) {
                for (label in listOf(BaseUser.CLASS, User.CLASS, Guest.CLASS, "UnknownTrack3Type")) {
                    fun ids(query: GremlinQuery) =
                        YTDBEntityIterable.query(tx.getStore(), query, poly).map { it.id }

                    val source = GremlinQuery.Labeled(GremlinQuery.all, label)
                    val local = GremlinQuery.AndThen(base, GremlinBlock.Not(GremlinBlock.HasLabel(label)))
                    val sourceResult = runCatching { ids(GremlinQuery.Aggregate(base, source) { P.without(it) }) }
                    val localResult = runCatching { ids(local) }
                    val message = "Class-source versus local exclusion: $label poly=$poly"
                    if (label == "UnknownTrack3Type") {
                        assertEquals(sourceResult.exceptionOrNull()?.javaClass,
                            localResult.exceptionOrNull()?.javaClass, message)
                        if (sourceResult.isSuccess) {
                            assertEquals(sourceResult.getOrThrow(), localResult.getOrThrow(), message)
                        }
                    } else {
                        val expectedNames = when {
                            label == BaseUser.CLASS -> emptyList()
                            !poly -> listOf("base1")
                            label == User.CLASS -> listOf("base1", "guest1")
                            else -> listOf("base1", "user1")
                        }
                        val expectedIds = expectedNames.map { entities.getValue(it).id }
                        assertEquals(expectedIds, sourceResult.getOrThrow(), "Class-source: $message")
                        assertEquals(expectedIds, localResult.getOrThrow(), "Local exclusion: $message")
                    }
                }
            }
        }
    }

    @Test
    fun `typed exclusion negates the complete property predicate and preserves ordered identities`() {
        givenUserHierarchy()
        val subclass = "PropertyCollisionUser"
        youTrackDb.withSession { session ->
            session.getOrCreateVertexClass(subclass)
                .addSuperClass(session.getOrCreateVertexClass(User.CLASS))
        }
        // Unique ranks define the left order independently of class-scan and record-ID order.
        val rows = listOf(
            Triple(BaseUser.CLASS, "collision", 70),
            Triple(subclass, "collision", 60),
            Triple(User.CLASS, "other", 50),
            Triple(Guest.CLASS, "collision", 40),
            Triple(User.CLASS, "collision", 30),
            Triple(subclass, "other", 20),
            Triple(BaseUser.CLASS, "other", 10)
        )
        withStoreTx { tx ->
            rows.forEach { (type, name, rank) ->
                tx.newEntity(type).apply {
                    setProperty("name", name)
                    setProperty("collisionOrder", rank)
                }
            }
        }
        withStoreTx { tx ->
            val idsByKey = tx.getAll(BaseUser.CLASS).associate {
                (it.type to it.getProperty("name").toString()) to it.id
            }
            fun idsFor(vararg keys: Pair<String, String>) = keys.map { idsByKey.getValue(it) }

            val baseCollision = BaseUser.CLASS to "collision"
            val subclassCollision = subclass to "collision"
            val userOther = User.CLASS to "other"
            val guestCollision = Guest.CLASS to "collision"
            val userCollision = User.CLASS to "collision"
            val subclassOther = subclass to "other"
            val baseOther = BaseUser.CLASS to "other"
            val source = GremlinQuery.Labeled(
                GremlinQuery.Where(GremlinBlock.PropNotNull("collisionOrder")), BaseUser.CLASS)
            val order = GremlinBlock.Sort(
                GremlinBlock.Sort.ByProp("collisionOrder"), GremlinBlock.SortDirection.DESC)
            val left = source.then(order)
            val right = GremlinQuery.Labeled(
                GremlinQuery.Where(GremlinBlock.PropEqual("name", "collision")), User.CLASS)
            val selected = left.difference(right)
            val filteredSource = source.difference(right)
            assertFalse(filteredSource.containsAggregate())
            for (poly in listOf(true, false)) {
                fun ids(query: GremlinQuery) =
                    YTDBEntityIterable.query(tx.getStore(), query, poly).map { it.id }

                val expectedLeft = if (poly) {
                    idsFor(baseCollision, subclassCollision, userOther, guestCollision,
                        userCollision, subclassOther, baseOther)
                } else {
                    idsFor(baseCollision, baseOther)
                }
                val expectedRight = if (poly) {
                    idsFor(userCollision, subclassCollision)
                } else {
                    idsFor(userCollision)
                }
                val expectedKept = if (poly) {
                    idsFor(baseCollision, userOther, guestCollision, subclassOther, baseOther)
                } else {
                    idsFor(baseCollision, baseOther)
                }
                assertEquals(expectedLeft, ids(left), "independent left order poly=$poly")
                assertEquals(expectedRight.toSet(), ids(right).toSet(), "typed RHS identities poly=$poly")
                assertEquals(expectedKept, ids(selected), "complete RHS exclusion poly=$poly")
                assertEquals(expectedKept, ids(filteredSource.then(order)), "fused complete RHS poly=$poly")
                assertEquals(expectedKept.take(2), ids(selected.then(GremlinBlock.Limit(2))),
                    "first page poly=$poly")
                assertEquals(expectedKept.drop(1).take(2),
                    ids(selected.then(GremlinBlock.Skip(1)).then(GremlinBlock.Limit(2))),
                    "offset page poly=$poly")
            }
        }
    }

    @Test
    fun `typed membership identities composition terminals and left order`() {
        givenUserHierarchy()
        withStoreTx { tx ->
            tx.createUser(User.CLASS, "user2")
            tx.createUser(Guest.CLASS, "user1")
        }
        // Logical construction needs no session or schema read.
        val base = GremlinQuery.Labeled(GremlinQuery.all, BaseUser.CLASS)
        val user = GremlinQuery.Labeled(GremlinQuery.Where(GremlinBlock.PropEqual("name", "user1")), User.CLASS)
        val guest = GremlinQuery.Labeled(GremlinQuery.all, Guest.CLASS)
        val selected = base.difference(user)
        withStoreTx { tx ->
            // The two user1 names have different types and distinct identity-oracle keys.
            val entities = tx.getAll(BaseUser.CLASS).associateBy {
                if (it.type == Guest.CLASS && it.getProperty("name") == "user1") "guest-user1"
                else it.getProperty("name").toString()
            }
            assertEquals(5, entities.size)
            assertTrue(entities.getValue("user1").id != entities.getValue("guest-user1").id)
            for (poly in listOf(true, false)) {
                fun check(query: GremlinQuery, names: Set<String>, driver: GremlinQuery? = null) {
                    val iterable = YTDBEntityIterable.query(tx.getStore(), query, poly)
                    val expectedIds = names.map { entities.getValue(it).id }.toSet()
                    assertEquals(expectedIds, iterable.map { it.id }.toSet(), "$query poly=$poly")
                    entities.forEach { (name, entity) ->
                        assertEquals(name in names, iterable.contains(entity), "contains $name poly=$poly")
                    }
                    if (driver != null) {
                        val expectedOrder = YTDBEntityIterable.query(tx.getStore(), driver, poly)
                            .map { it.id }.filter { it in expectedIds }
                        assertEquals(expectedOrder, iterable.map { it.id }, "left order poly=$poly")
                    }
                }
                val kept = if (poly) setOf("base1", "guest1", "user2", "guest-user1") else setOf("base1")
                check(selected, kept, base)
                check(selected.intersect(base), kept, selected)
                check(selected.difference(guest), if (poly) setOf("base1", "user2") else setOf("base1"), selected)
                // The complete right exclusion stays inside the outer negation.
                val nestedExclusion = selected.difference(base.difference(guest))
                assertFalse(nestedExclusion.containsAggregate())
                check(nestedExclusion, if (poly) setOf("guest1", "guest-user1") else emptySet(), selected)
                val catalogLike = selected.intersect(base.difference(guest))
                assertFalse(catalogLike.containsAggregate())
                check(catalogLike, if (poly) setOf("base1", "user2") else setOf("base1"), selected)
                check(selected.union(user), if (poly) entities.keys else setOf("base1", "user1"))
                check(base.intersect(user), if (poly) setOf("user1") else emptySet(), base)
                check(user.intersect(base), if (poly) setOf("user1") else emptySet(), user)
                val descending = base.then(GremlinBlock.Sort(
                    GremlinBlock.Sort.ByProp("name"), GremlinBlock.SortDirection.DESC))
                check(descending.difference(user), kept, descending)
                val reversed = base.then(GremlinBlock.Reverse)
                check(reversed.difference(user), kept, reversed)
                val nested = GremlinQuery.Labeled(GremlinQuery.Where(GremlinBlock.Not(
                    GremlinBlock.Or(GremlinBlock.PropEqual("name", "absent"),
                        GremlinBlock.Not(GremlinBlock.PropEqual("name", "user1"))))), User.CLASS)
                check(base.difference(nested), kept, base)
                val fallbackOperands = listOf(
                    base.then(GremlinBlock.Limit(1)),
                    base.unionAll(user),
                    base.then(GremlinBlock.OutLink("missing")),
                    GremlinQuery.Labeled(base.then(GremlinBlock.Skip(1)), BaseUser.CLASS)
                )
                fallbackOperands.forEach { operand ->
                    val rightIds = YTDBEntityIterable.query(tx.getStore(), operand, poly).map { it.id }.toSet()
                    val expected = kept.filter { entities.getValue(it).id in rightIds }.toSet()
                    check(selected.intersect(operand), expected, selected)
                }
            }
        }
    }

    @Test
    fun `null equality on an absent property keeps the class source fallback`() {
        givenUserHierarchy()
        withStoreTx { tx -> tx.newEntity(User.CLASS) }
        withStoreTx { tx ->
            val absentId = tx.getAll(User.CLASS).single { it.getProperty("name") == null }.id
            val base = GremlinQuery.Labeled(GremlinQuery.all, BaseUser.CLASS)
            val right = GremlinQuery.Labeled(
                GremlinQuery.Where(GremlinBlock.PropEqual("name", null)), User.CLASS)
            for (poly in listOf(true, false)) {
                for (native in listOf(false, true)) {
                    fun ids(query: GremlinQuery): List<jetbrains.exodus.entitystore.EntityId> {
                        val iterable = YTDBEntityIterable.query(tx.getStore(), query, poly)
                        if (!native) return iterable.map { it.id }
                        val traversal = iterable.traversal()
                        traversal.asAdmin().strategies.removeStrategies(
                            com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy::class.java)
                        return YTDBEntityIterator.of(traversal, tx.getStore()).use { iterator ->
                            iterator.asSequence().map { it.id }.toList()
                        }
                    }
                    val positive = base.intersect(right) as GremlinQuery.Aggregate
                    val negative = base.difference(right) as GremlinQuery.Aggregate
                    assertSame(base, positive.left)
                    assertSame(right, positive.right)
                    assertSame(base, negative.left)
                    assertSame(right, negative.right)
                    val removed = if (poly) setOf(absentId) else emptySet()
                    assertEquals(removed, ids(positive).toSet(), "intersection poly=$poly native=$native")
                    assertEquals(ids(base).filter { it !in removed }, ids(negative),
                        "difference poly=$poly native=$native")
                }
            }
        }
    }

    @Test
    fun `right dedup cannot swap the observed left identity order`() {
        givenUserHierarchy()
        withStoreTx { tx -> tx.createUser(User.CLASS, "user2") }
        withStoreTx { tx ->
            val left = GremlinQuery.Labeled(GremlinQuery.Where(GremlinBlock.PropNotNull("name")), BaseUser.CLASS)
            val users = tx.getAll(User.CLASS).associateBy { it.id }
            val expected = YTDBEntityIterable.query(tx.getStore(), left).map { it.id }.filter { it in users }
            assertEquals(2, expected.size)
            val firstName = users.getValue(expected.first()).getProperty("name").toString()
            val direction = if (firstName == "user1") GremlinBlock.SortDirection.DESC else GremlinBlock.SortDirection.ASC
            val right = GremlinQuery.Labeled(GremlinQuery.Where(GremlinBlock.PropNotNull("name")), User.CLASS)
                .then(GremlinBlock.Sort(GremlinBlock.Sort.ByProp("name"), direction)).then(GremlinBlock.Dedup)
            assertEquals(expected.reversed(), YTDBEntityIterable.query(tx.getStore(), right).map { it.id })
            val result = left.intersect(right) as GremlinQuery.Aggregate
            assertSame(left, result.left)
            assertSame(right, result.right)
            assertEquals(expected, YTDBEntityIterable.query(tx.getStore(), result).map { it.id })
            assertEquals(expected.take(1), YTDBEntityIterable.query(tx.getStore(), result.then(GremlinBlock.Limit(1))).map { it.id })
            assertEquals(expected.drop(1).take(1), YTDBEntityIterable.query(tx.getStore(),
                result.then(GremlinBlock.Skip(1)).then(GremlinBlock.Limit(1))).map { it.id })

            // Paging the oppositely ordered RHS must select its identity, not the left page.
            val pagedRight = right.then(GremlinBlock.Skip(1)).then(GremlinBlock.Limit(1))
            val expectedRightPage = expected.take(1)
            assertEquals(expectedRightPage, YTDBEntityIterable.query(tx.getStore(), pagedRight).map { it.id })
            val pagedResult = left.intersect(pagedRight) as GremlinQuery.Aggregate
            assertSame(left, pagedResult.left)
            assertSame(pagedRight, pagedResult.right)
            assertEquals(expectedRightPage, YTDBEntityIterable.query(tx.getStore(), pagedResult).map { it.id })
        }
    }

    @Test
    fun `typed local conjunction includes shared multiple inheritance descendants`() {
        givenUserHierarchy()
        youTrackDb.withSession { session ->
            val shared = session.getOrCreateVertexClass("SharedTrack3User")
            shared.addSuperClass(session.getOrCreateVertexClass(User.CLASS))
            shared.addSuperClass(session.getOrCreateVertexClass(Guest.CLASS))
        }
        withStoreTx { tx -> tx.createUser("SharedTrack3User", "shared") }
        withStoreTx { tx ->
            val sharedId = tx.getAll("SharedTrack3User").first()!!.id
            val user = GremlinQuery.Labeled(GremlinQuery.all, User.CLASS)
            val guest = GremlinQuery.Labeled(GremlinQuery.all, Guest.CLASS)
            for (poly in listOf(true, false)) {
                listOf(user.intersect(guest), guest.intersect(user)).forEach { query ->
                    assertEquals(if (poly) listOf(sharedId) else emptyList(),
                        YTDBEntityIterable.query(tx.getStore(), query, poly).map { it.id })
                }
                val explicit = GremlinQuery.Aggregate(user, guest) { P.within(it) }
                assertEquals(YTDBEntityIterable.query(tx.getStore(), explicit, poly).map { it.id },
                    YTDBEntityIterable.query(tx.getStore(), user.intersect(guest), poly).map { it.id })
            }
        }
    }

    private fun givenUserHierarchy() {
        youTrackDb.withSession { session ->
            val baseClass = session.getOrCreateVertexClass(BaseUser.CLASS)
            listOf(
                session.getOrCreateVertexClass(User.CLASS),
                session.getOrCreateVertexClass(Guest.CLASS)
            ).forEach { it.addSuperClass(baseClass) }
        }
        withStoreTx { tx ->
            tx.createUser(BaseUser.CLASS, "base1")
            tx.createUser(User.CLASS, "user1")
            tx.createUser(Guest.CLASS, "guest1")
        }
    }

    @Test
    fun `non-polymorphic getAll returns exact type only`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            assertNamesExactly(result, "base1")
        }
    }

    @Test
    fun `polymorphic getAll returns subclasses (default)`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All)

            assertNamesExactly(result, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `flag survives single-operand chaining`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val afterSkip = nonPoly.skip(1) as YTDBEntityIterable
            assertFalse(afterSkip.polymorphic)
            // Non-poly BaseUser=[base1], skip(1)=[] ; poly leak would give [user1, guest1]
            assertNamesExactly(afterSkip)

            val afterTake = nonPoly.take(10) as YTDBEntityIterable
            assertFalse(afterTake.polymorphic)
            assertNamesExactly(afterTake, "base1")

            val afterDistinct = nonPoly.distinct() as YTDBEntityIterable
            assertFalse(afterDistinct.polymorphic)

            val afterReverse = nonPoly.reverse() as YTDBEntityIterable
            assertFalse(afterReverse.polymorphic)
            // Non-poly BaseUser=[base1], reverse=[base1] ; poly leak would give 3 results
            assertNamesExactly(afterReverse, "base1")
        }
    }

    @Test
    fun `flag survives combination methods`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly1 = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPoly2 = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val intersected = nonPoly1.intersect(nonPoly2) as YTDBEntityIterable
            assertFalse(intersected.polymorphic)
            // End-to-end: disjoint types produce empty intersection
            assertEquals(0, intersected.count())

            val unioned = nonPoly1.union(nonPoly2) as YTDBEntityIterable
            assertFalse(unioned.polymorphic)
            // Non-poly: [base1] ∪ [user1] = [base1, user1] ; poly leak on BaseUser would add guest1
            assertNamesExactly(unioned, "base1", "user1")

            val minused = nonPoly1.minus(nonPoly2) as YTDBEntityIterable
            assertFalse(minused.polymorphic)
            // End-to-end: minus of disjoint non-polymorphic iterables preserves left operand results
            assertNamesExactly(minused, "base1")

            val concatenated = nonPoly1.concat(nonPoly2) as YTDBEntityIterable
            assertFalse(concatenated.polymorphic)
            // Non-poly: [base1] ++ [user1] = [base1, user1] ; poly leak would give 4 results
            assertNamesExactlyInOrder(concatenated, "base1", "user1")
        }
    }

    @Test
    fun `flag propagates through selectMany`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val selected = nonPoly.selectMany("someLink") as YTDBEntityIterable
            assertFalse(selected.polymorphic)
        }
    }

    @Test
    fun `default polymorphic flag is true`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All)

            assertTrue(poly.polymorphic)
        }
    }

    @Test
    fun `non-polymorphic query on leaf type returns same results as polymorphic`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            assertNamesExactly(poly, "user1")
            assertNamesExactly(nonPoly, "user1")
        }
    }

    @Test
    fun `findLinks propagates polymorphic flag from the receiver`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyReceiver = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val polyEntities = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)

            // findLinks returns a subset of the receiver, so the receiver's flag governs the result.
            val result = nonPolyReceiver.findLinks(polyEntities, "someLink") as YTDBEntityIterable
            assertFalse(result.polymorphic, "findLinks should propagate the receiver flag, not the entities-parameter flag")
        }
    }

    @Test
    fun `flag preserved when combining with EMPTY`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val empty = YTDBEntityIterable.EMPTY

            assertFalse((nonPoly.union(empty) as YTDBEntityIterable).polymorphic)
            assertFalse((nonPoly.minus(empty) as YTDBEntityIterable).polymorphic)
            assertFalse((nonPoly.concat(empty) as YTDBEntityIterable).polymorphic)
            // intersect with EMPTY returns EMPTY itself (acceptable: empty result)
            assertSame(nonPoly.intersect(empty), YTDBEntityIterable.EMPTY)
        }
    }

    @Test
    fun `non-polymorphic distinct returns exact-type results end-to-end`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            // distinct() goes through modify(), producing a new iterable;
            // verify the flag propagates AND the traversal respects it
            assertNamesExactly(nonPoly.distinct(), "base1")
        }
    }

    // --- UnionAll traversal propagation tests ---

    @Test
    fun `non-polymorphic union of inherited types returns only exact-type instances`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            // BaseUser is parent of User — these types have an inheritance relationship.
            // Each non-polymorphic query should return only exact instances of its type:
            //   BaseUser → ["base1"], User → ["user1"]
            // Their union should return exactly ["base1", "user1"] — NOT "guest1".
            // If the engine makes this polymorphic, BaseUser would also match user1+guest1.
            val nonPolyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyUser = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val unioned = nonPolyBase.union(nonPolyUser)
            assertNamesExactly(unioned, "base1", "user1")
        }
    }

    @Test
    fun `non-polymorphic concat of inherited types returns only exact-type instances`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyUser = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val concatenated = nonPolyBase.concat(nonPolyUser)
            assertNamesExactlyInOrder(concatenated, "base1", "user1")
        }
    }

    @Test
    fun `nested concat propagates non-polymorphic flag through inner UnionAll`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            // A.concat(B).concat(C) creates UnionAll([UnionAll([A,B]), C]).
            // The inner UnionAll's continueTraversal receives an anonymous traversal
            // that had OptionsStrategy added by the outer subtraversals(). Without the
            // Track 5 fix (propagating OptionsStrategy to child traversals),
            // the inner children would lose the polymorphicQuery config.
            val nonPolyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyUser = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyGuest = YTDBEntityIterable.where(Guest.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val nested = nonPolyBase.concat(nonPolyUser).concat(nonPolyGuest)
            assertNamesExactlyInOrder(nested, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic union then concat triggers continueTraversal on inner UnionAll`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyUser = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyGuest = YTDBEntityIterable.where(Guest.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            // A.union(B) produces Order(UnionAll([A,B]), Dedup).
            // concat does NOT flatten, so .concat(C) produces:
            //   UnionAll([Order(UnionAll([A,B]), Dedup), C])
            // The outer UnionAll.startTraversal propagates OptionsStrategy to child traversals.
            // Order.continueTraversal delegates to UnionAll.continueTraversal, which must
            // extract OptionsStrategy from the parent traversal's admin interface.
            // If continueTraversal fails to propagate: BaseUser(poly) returns {base1,user1,guest1},
            // User(poly) returns {user1,guest1}, union dedup → {base1,user1,guest1},
            // concat Guest(non-poly) → [base1,user1,guest1,guest1] (4 results, not 3).
            val result = nonPolyBase.union(nonPolyUser).concat(nonPolyGuest)
            assertNamesExactlyInOrder(result, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic union then minus excludes subtypes from parent`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyUser = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyGuest = YTDBEntityIterable.where(Guest.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            // (User ∪ Guest) \ BaseUser
            // Non-poly: ({user1} ∪ {guest1}) \ {base1} = {user1, guest1} (disjoint)
            // If poly leaked on BaseUser: \ {base1,user1,guest1} = empty
            val result = nonPolyUser.union(nonPolyGuest).minus(nonPolyBase)
            assertNamesExactly(result, "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic class scan on the left of an aggregate stays non-polymorphic`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyGuest = YTDBEntityIterable.where(Guest.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            // BaseUser \ Guest as an Aggregate: the BaseUser scan is the embedded left child.
            // Non-poly: {base1} \ {guest1} = {base1}.
            // If the child scan turned polymorphic: {base1,user1,guest1} \ {guest1} = {base1,user1}.
            val aggregate = GremlinQuery.Aggregate(nonPolyBase.query, nonPolyGuest.query) { P.without(it) }
            val result = YTDBEntityIterableImpl(tx.getStore(), aggregate, polymorphic = false)

            assertNamesExactly(result, "base1")
        }
    }

    @Test
    fun `non-polymorphic union then intersect with parent type returns empty`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyUser = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyGuest = YTDBEntityIterable.where(Guest.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            // (User ∪ Guest) ∩ BaseUser
            // Non-poly: ({user1} ∪ {guest1}) ∩ {base1} = empty (disjoint)
            // If poly leaked on BaseUser: ∩ {base1,user1,guest1} = {user1, guest1}
            // The optimizer distributes via O20b and re-applies the label (O20b-fix).
            val result = nonPolyUser.union(nonPolyGuest).intersect(nonPolyBase)
            assertEquals(0, result.count())
        }
    }

    @Test
    fun `non-polymorphic minus then union preserves exact-type semantics`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyUser = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyGuest = YTDBEntityIterable.where(Guest.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            // (BaseUser \ Guest) ∪ User
            // Non-poly: ({base1} \ {guest1}) ∪ {user1} = {base1} ∪ {user1} = {base1, user1}
            // If poly leaked on BaseUser: ({base1,user1,guest1} \ {guest1}) ∪ {user1}
            //   = {base1, user1} ∪ {user1} = {base1, user1} — same result (not falsifiable).
            // Use reverse operands to make it falsifiable:
            // (User \ BaseUser) ∪ Guest
            // Non-poly: ({user1} \ {base1}) ∪ {guest1} = {user1, guest1}
            // If poly leaked on BaseUser: ({user1} \ {base1,user1,guest1}) ∪ {guest1}
            //   = {} ∪ {guest1} = {guest1}
            val result = nonPolyUser.minus(nonPolyBase).union(nonPolyGuest)
            assertNamesExactly(result, "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic intersect then union returns correct result`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyUser = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyGuest = YTDBEntityIterable.where(Guest.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            // (BaseUser ∩ User) ∪ Guest = ["guest1"]
            // BaseUser is ["base1"], User is ["user1"] — disjoint, so intersect is empty.
            val result = nonPolyBase.intersect(nonPolyUser).union(nonPolyGuest)
            assertNamesExactly(result, "guest1")
        }
    }

    @Test
    fun `non-polymorphic union then selectMany propagates flag`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyUser = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            // No entities have "someLink" links, so we only verify flag propagation.
            val unioned = nonPolyBase.union(nonPolyUser) as YTDBEntityIterable
            val selected = unioned.selectMany("someLink") as YTDBEntityIterable
            assertFalse(selected.polymorphic, "selectMany on union result should propagate non-polymorphic flag")
        }
    }

    @Test
    fun `union as findLinks entities parameter does not override the receiver flag`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val polyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)
            val polyUser = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)
            // Receiver is non-polymorphic — different from the entities parameter.
            val nonPolyReceiver = YTDBEntityIterable.where(Guest.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            // findLinks returns a subset of the receiver, so the receiver's flag governs the result
            // even when the entities parameter (the union) carries the opposite flag.
            // No entities have "someLink" links.
            val unioned = polyBase.union(polyUser)
            val found = nonPolyReceiver.findLinks(unioned, "someLink") as YTDBEntityIterable
            assertFalse(found.polymorphic, "findLinks should propagate the receiver flag, not the union entities-parameter flag")
        }
    }

    @Test
    fun `non-polymorphic union then single-operand chain returns correct results`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPolyBase = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPolyUser = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            // (BaseUser ∪ User).take(10).distinct()
            val result = nonPolyBase.union(nonPolyUser).take(10).distinct()
            assertNamesExactly(result, "base1", "user1")
        }
    }

    // --- Combination flag validation tests ---

    @Test
    fun `mixed-flag intersect throws in both directions with descriptive message`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val ex1 = assertFailsWith<IllegalArgumentException> { poly.intersect(nonPoly) }
            assertTrue(ex1.message!!.startsWith("Cannot combine a polymorphic iterable with a non-polymorphic"))

            val ex2 = assertFailsWith<IllegalArgumentException> { nonPoly.intersect(poly) }
            assertTrue(ex2.message!!.startsWith("Cannot combine a non-polymorphic iterable with a polymorphic"))
        }
    }

    @Test
    fun `mixed-flag union throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val ex1 = assertFailsWith<IllegalArgumentException> { poly.union(nonPoly) }
            assertTrue(ex1.message!!.contains("polymorphic flag"))
            val ex2 = assertFailsWith<IllegalArgumentException> { nonPoly.union(poly) }
            assertTrue(ex2.message!!.contains("polymorphic flag"))
        }
    }

    @Test
    fun `mixed-flag minus throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val ex1 = assertFailsWith<IllegalArgumentException> { poly.minus(nonPoly) }
            assertTrue(ex1.message!!.contains("polymorphic flag"))
            val ex2 = assertFailsWith<IllegalArgumentException> { nonPoly.minus(poly) }
            assertTrue(ex2.message!!.contains("polymorphic flag"))
        }
    }

    @Test
    fun `mixed-flag concat throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val ex1 = assertFailsWith<IllegalArgumentException> { poly.concat(nonPoly) }
            assertTrue(ex1.message!!.contains("polymorphic flag"))
            val ex2 = assertFailsWith<IllegalArgumentException> { nonPoly.concat(poly) }
            assertTrue(ex2.message!!.contains("polymorphic flag"))
        }
    }

    @Test
    fun `mixed-flag intersectSavingOrder throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val ex1 = assertFailsWith<IllegalArgumentException> { poly.intersectSavingOrder(nonPoly) }
            assertTrue(ex1.message!!.contains("polymorphic flag"))
            val ex2 = assertFailsWith<IllegalArgumentException> { nonPoly.intersectSavingOrder(poly) }
            assertTrue(ex2.message!!.contains("polymorphic flag"))
        }
    }

    @Test
    fun `same-flag polymorphic true combination succeeds with correct results`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly1 = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)
            val poly2 = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)

            val unioned = poly1.union(poly2) as YTDBEntityIterable
            assertTrue(unioned.polymorphic)
            assertNamesExactly(unioned, "base1", "user1", "guest1")

            val intersected = poly1.intersect(poly2) as YTDBEntityIterable
            assertTrue(intersected.polymorphic)
            assertNamesExactly(intersected, "user1")

            val minused = poly1.minus(poly2) as YTDBEntityIterable
            assertTrue(minused.polymorphic)
            assertNamesExactly(minused, "base1", "guest1")

            val concatenated = poly1.concat(poly2) as YTDBEntityIterable
            assertTrue(concatenated.polymorphic)
            assertEquals(4, concatenated.count())
        }
    }

    @Test
    fun `EMPTY sentinel has polymorphic true by default`() {
        assertTrue(YTDBEntityIterable.EMPTY.polymorphic)
    }

    @Test
    fun `EMPTY as left operand preserves right operand flag`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val empty = YTDBEntityIterable.EMPTY

            // EMPTY.union returns right directly — flag preserved
            val unionResult = empty.union(nonPoly)
            assertFalse((unionResult as YTDBEntityIterable).polymorphic)
            assertNamesExactly(unionResult, "base1")

            // EMPTY.concat returns right directly — flag preserved
            val concatResult = empty.concat(nonPoly)
            assertFalse((concatResult as YTDBEntityIterable).polymorphic)
            assertNamesExactly(concatResult, "base1")

            // EMPTY.intersect returns EMPTY itself
            assertSame(empty.intersect(nonPoly), YTDBEntityIterable.EMPTY)

            // EMPTY.minus returns EMPTY itself
            assertSame(empty.minus(nonPoly), YTDBEntityIterable.EMPTY)

            // EMPTY.intersectSavingOrder returns EMPTY itself
            assertSame(empty.intersectSavingOrder(nonPoly), YTDBEntityIterable.EMPTY)
        }
    }

    @Test
    fun `chained combination preserves flag and validates correctly`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly1 = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPoly2 = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPoly3 = YTDBEntityIterable.where(Guest.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            val combined = nonPoly1.union(nonPoly2).union(nonPoly3)
            assertFalse((combined as YTDBEntityIterable).polymorphic)
            assertNamesExactly(combined, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `chained combination result rejects mismatched flag`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly1 = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val nonPoly2 = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val poly = YTDBEntityIterable.where(Guest.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)

            val combined = nonPoly1.union(nonPoly2) // polymorphic=false
            assertFailsWith<IllegalArgumentException> { combined.intersect(poly) }
        }
    }

    // --- ByIds mixed-polymorphic validation tests ---
    // ByIds iterables are always polymorphic=true (default). Combining them with
    // non-polymorphic iterables is rejected because the polymorphic flag propagates
    // to child subtraversals and can cause the engine to silently drop results.

    @Test
    fun `ByIds with non-polymorphic minus throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val base1 = tx.find(BaseUser.CLASS, "name", "base1").first()!!
            val byIds = YTDBEntityIterable.query(tx.getStore(), GremlinQuery.ByIds(listOf((base1.id as YTDBEntityId).asOId())))

            assertFailsWith<IllegalArgumentException> { nonPoly.minus(byIds) }
        }
    }

    @Test
    fun `ByIds with non-polymorphic intersect throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val base1 = tx.find(BaseUser.CLASS, "name", "base1").first()!!
            val byIds = YTDBEntityIterable.query(tx.getStore(), GremlinQuery.ByIds(listOf((base1.id as YTDBEntityId).asOId())))

            assertFailsWith<IllegalArgumentException> { nonPoly.intersect(byIds) }
        }
    }

    @Test
    fun `ByIds with non-polymorphic union throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val user1 = tx.find(User.CLASS, "name", "user1").first()!!
            val byIds = YTDBEntityIterable.query(tx.getStore(), GremlinQuery.ByIds(listOf((user1.id as YTDBEntityId).asOId())))

            assertFailsWith<IllegalArgumentException> { nonPoly.union(byIds) }
        }
    }

    @Test
    fun `ByIds with polymorphic union works correctly`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)
            val user1 = tx.find(User.CLASS, "name", "user1").first()!!
            val byIds = YTDBEntityIterable.query(tx.getStore(), GremlinQuery.ByIds(listOf((user1.id as YTDBEntityId).asOId())))

            val intersected = poly.intersect(byIds)
            assertNamesExactly(intersected, "user1")
        }
    }

    @Test
    fun `mixed-flag non-ByIds throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)

            assertFailsWith<IllegalArgumentException> { poly.intersect(nonPoly) }
            assertFailsWith<IllegalArgumentException> { nonPoly.union(poly) }
        }
    }

    @Test
    fun `UnionAll subtraversals does not mutate shared EmptyGraph strategies`() {
        givenUserHierarchy()
        // D9 invariant: GremlinQuery.UnionAll.subtraversals() must not mutate
        // the TraversalStrategies.GlobalCache[EmptyGraph] container that
        // anonymous traversals alias to. The CME observed under the pre-fix
        // code was a symptom of this invariant being violated by concurrent
        // addStrategies calls on the shared LinkedHashSet; testing the
        // invariant directly catches the root cause deterministically,
        // without needing to reproduce the race.
        //
        // The baseline snapshot is captured as a `List` and compared element-wise
        // by identity (not by `equals`): AbstractTraversalStrategy.equals compares
        // by class, so a same-class replacement of a pre-existing OptionsStrategy
        // would pass a set-equality check while still violating the invariant.
        val shared = TraversalStrategies.GlobalCache.getStrategies(EmptyGraph::class.java)
        val baseline = shared.toList()
        withStoreTx { tx ->
            val a = YTDBEntityIterable.where(BaseUser.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            val b = YTDBEntityIterable.where(User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false)
            a.union(b).count()
        }
        val post = shared.toList()
        assertEquals(
            baseline.size, post.size,
            "UnionAll.subtraversals() changed the size of TraversalStrategies.GlobalCache[EmptyGraph]"
        )
        baseline.indices.forEach { i ->
            assertSame(
                baseline[i], post[i],
                "UnionAll.subtraversals() replaced the strategy at index $i in TraversalStrategies.GlobalCache[EmptyGraph]"
            )
        }
    }
}
