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
import kotlin.test.assertEquals

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
            val result = tx.findContaining(BaseUser.CLASS, "name", "base", false, polymorphic = false)
            assertNamesExactly(result, "base1")

            // Default (polymorphic) with a pattern matching all names
            val polyResult = tx.findContaining(BaseUser.CLASS, "name", "1", false)
            assertNamesExactly(polyResult, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `non-polymorphic findStartingWith returns exact type only`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = tx.findStartingWith(BaseUser.CLASS, "name", "base", polymorphic = false)
            assertNamesExactly(result, "base1")
        }
    }

    @Test
    fun `non-polymorphic findWithBlob delegates to findWithProp`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            // findWithBlob delegates to findWithProp; test that polymorphic flag propagates
            val result = tx.findWithBlob(BaseUser.CLASS, "name", polymorphic = false)
            assertNamesExactly(result, "base1")
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
            val result = tx.findIds(BaseUser.CLASS, Long.MIN_VALUE, Long.MAX_VALUE, polymorphic = false)
            assertEquals(1, result.count())

            val polyResult = tx.findIds(BaseUser.CLASS, Long.MIN_VALUE, Long.MAX_VALUE)
            assertEquals(3, polyResult.count())
        }
    }
}
