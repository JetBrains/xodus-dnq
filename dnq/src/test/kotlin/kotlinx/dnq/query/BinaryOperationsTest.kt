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


import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.junit.Test

class BinaryOperationsTest : DBTest() {

    class User(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<User>() {
            fun new(name: String) = new {
                this.name = name
            }
        }

        var name by xdStringProp()
        var group: Group? by xdLink0_1(Group::users)
    }

    class Group(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Group>()

        val users by xdLink0_N(User::group)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(User, Group)
    }

    private fun IntRange.toUsers() = map { i -> User.new(i.toString()) }

    private fun List<User>.toQuery(): XdQuery<User> {
        return toTypedArray()
                .let { userArray -> User.queryOf(*userArray) }
    }

    @Test
    fun union() {
        transactional { txn ->
            val pack1 = (1..100).toUsers()
            val pack2 = (101..200).toUsers()
            txn.flush()

            val someUsers = pack1.toQuery()
            val otherUsers = pack2.toQuery()

            assertThat(someUsers union otherUsers).hasSize(200)
            assertThat(User.all() union someUsers).hasSize(200)
            assertThat(User.all() union otherUsers).hasSize(200)
            assertThat(User.all() union User.all()).hasSize(200)
            assertThat(User.emptyQuery() union someUsers).hasSize(100)
            assertThat(someUsers union User.emptyQuery()).hasSize(100)
            assertThat(someUsers union someUsers).hasSize(100)
            assertThat(someUsers union otherUsers.first()).hasSize(101)
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

            assertThat(someUsers intersect otherUsers).hasSize(20)
            assertThat(User.all() intersect someUsers).hasSize(110)
            assertThat(User.all() intersect otherUsers).hasSize(110)
            assertThat(someUsers intersect someUsers).hasSize(110)
            assertThat(User.all() intersect User.all() exclude User.all().first()).hasSize(199)
            assertThat(User.emptyQuery() intersect someUsers).hasSize(0)
            assertThat(someUsers intersect User.emptyQuery()).hasSize(0)
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

            assertThat(someUsers exclude otherUsers).hasSize(90)
            assertThat(User.all() exclude someUsers).hasSize(90)
            assertThat(User.all() exclude otherUsers).hasSize(90)
            assertThat(someUsers exclude someUsers).hasSize(0)
            assertThat(someUsers exclude User.emptyQuery()).hasSize(110)
            assertThat(User.emptyQuery() exclude someUsers).hasSize(0)
            assertThat(User.all() exclude User.query(User::name eq "2")).hasSize(199)
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

            assertThat(someUsers intersect otherUsers).hasSize(20)
            assertThat(User.all() intersect someUsers).hasSize(110)
            assertThat(User.all() intersect otherUsers).hasSize(110)
            assertThat(someUsers.query(User::name startsWith "1").query(User::name startsWith "10")).hasSize(11)
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
            assertThat(Group.all() intersect User.all().mapDistinct(User::group)).containsExactly(group)
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
            assertThat(Group.all() union User.all().mapDistinct(User::group)).containsExactly(group)
        }
    }
}
