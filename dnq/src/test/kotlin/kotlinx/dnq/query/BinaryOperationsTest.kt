/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import kotlinx.dnq.*
import org.junit.Test

class BinaryOperationsTest : DBTest() {

    abstract class UserBase(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<UserBase>()

        var name by xdStringProp()
        var group: Group? by xdLink0_1(Group::users)
    }

    class GuestUser(entity: Entity) : UserBase(entity) {
        companion object : XdNaturalEntityType<GuestUser>() {
            fun new() = new {
                this.name = "guest"
            }
        }
    }

    class User(entity: Entity) : UserBase(entity) {
        companion object : XdNaturalEntityType<User>() {
            fun new(name: String) = new {
                this.name = name
            }
        }
    }

    class Group(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Group>()

        val users by xdLink0_N(UserBase::group)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(User, GuestUser, Group)
    }

    private fun IntRange.toUsers() = map { i -> User.new(i.toString()) }

    private fun List<UserBase>.toQuery(): XdQuery<UserBase> {
        return toTypedArray()
                .let { userArray -> UserBase.queryOf(*userArray) }
    }

    @Test
    fun union() {
        transactional { txn ->
            val pack1 = (1..100).toUsers()
            val pack2 = (101..200).toUsers()
            txn.flush()

            val someUsers = pack1.toQuery()
            val otherUsers = pack2.toQuery()

            assertQuery(someUsers union otherUsers).hasSize(200)
            assertQuery(User.all() union someUsers).hasSize(200)
            assertQuery(User.all() union otherUsers).hasSize(200)
            assertQuery(User.all() union User.all()).hasSize(200)
            assertQuery(User.emptyQuery() union someUsers).hasSize(100)
            assertQuery(someUsers union User.emptyQuery()).hasSize(100)
            assertQuery(someUsers union someUsers).hasSize(100)
            assertQuery(someUsers union otherUsers.first()).hasSize(101)
        }
    }

    @Test
    fun intersect() {
        transactional { txn ->
            val pack1 = (1..100).toUsers()
            val pack2 = (101..200).toUsers()
            txn.flush()

            val someUsers = (pack1 + pack2.take(10)).toQuery()
            val otherUsers = (pack1.take(10) + pack2).toQuery()

            assertQuery(someUsers intersect otherUsers).hasSize(20)
            assertQuery(User.all() intersect someUsers).hasSize(110)
            assertQuery(User.all() intersect otherUsers).hasSize(110)
            assertQuery(someUsers intersect someUsers).hasSize(110)
            assertQuery(User.all() intersect User.all() exclude User.all().first()).hasSize(199)
            assertQuery(User.emptyQuery() intersect someUsers).hasSize(0)
            assertQuery(someUsers intersect User.emptyQuery()).hasSize(0)
        }
    }

    @Test
    fun exclude() {
        transactional { txn ->
            val pack1 = (1..100).toUsers()
            val pack2 = (101..200).toUsers()
            txn.flush()

            val someUsers = (pack1 + pack2.take(10)).toQuery()
            val otherUsers = (pack1.take(10) + pack2).toQuery()

            assertQuery(someUsers exclude otherUsers).hasSize(90)
            assertQuery(User.all() exclude someUsers).hasSize(90)
            assertQuery(User.all() exclude otherUsers).hasSize(90)
            assertQuery(someUsers exclude someUsers).hasSize(0)
            assertQuery(someUsers exclude User.emptyQuery()).hasSize(110)
            assertQuery(User.emptyQuery() exclude someUsers).hasSize(0)
            assertQuery(User.all() exclude User.query(User::name eq "2")).hasSize(199)
        }
    }

    @Test
    fun complexQuery1() {
        transactional { txn ->
            val pack1 = (1..100).toUsers()
            val pack2 = (101..200).toUsers()
            (1000..1009).toUsers()
            txn.flush()

            val someUsers = (pack1 + pack2.take(10)).toQuery()
            val otherUsers = (pack1.take(10) + pack2).toQuery()

            assertQuery(someUsers intersect otherUsers).hasSize(20)
            assertQuery(User.all() intersect someUsers).hasSize(110)
            assertQuery(User.all() intersect otherUsers).hasSize(110)
            assertQuery(someUsers.query(User::name startsWith "1").query(User::name startsWith "10")).hasSize(11)
        }
    }

    @Test
    fun intersectAllToNullHolder() {
        val group = transactional {
            Group.new {
                users.add(User.new("first"))
                User.new("second")
            }
        }
        transactional {
            assertQuery(Group.all() intersect User.all().mapDistinct(User::group)).containsExactly(group)
        }
    }

    @Test
    fun unionAllToNullHolder() {
        val group = transactional {
            Group.new {
                users.add(User.new("first"))
                User.new("second")
            }
        }

        transactional {
            assertQuery(Group.all() union User.all().mapDistinct(User::group)).containsExactly(group)
        }
    }

    @Test
    fun `union of two sibling types should return query of the base type`() {
        transactional {
            GuestUser.new()
            (1..10).toUsers()
        }

        val allUsers = transactional {
            GuestUser.all() union User.all()
        }

        assertThat(allUsers.entityType.entityType).isEqualTo(UserBase.entityType)
    }

    @Test
    fun `sorting over an union of sibling types should return the full result`() {
        transactional {
            GuestUser.new()
            (1..10).toUsers()
        }

        transactional {
            val sorted = GuestUser.all().union(User.all()).sortedBy(UserBase::name)
            assertQuery(sorted).hasSize(11)
        }
    }

    @Test
    fun `concat of two sibling types should return query of the base type`() {
        transactional {
            GuestUser.new()
            (1..10).toUsers()
        }

        val allUsers = transactional {
            GuestUser.all() + User.all()
        }

        assertThat(allUsers.entityType.entityType).isEqualTo(UserBase.entityType)
    }

    @Test
    fun `sorting over a concat of sibling types should return the full result`() {
        transactional {
            GuestUser.new()
            (1..10).toUsers()
        }

        transactional {
            val sorted = GuestUser.all().plus(User.all()).sortedBy(UserBase::name)
            assertQuery(sorted).hasSize(11)
        }
    }
}
