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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.first
import kotlinx.dnq.query.toList
import org.junit.Test

class AssociationSimpleTest : DBTest() {

    class MyThing(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<MyThing>() {
            fun new() = new {
                System.out.println(this.ssss);
            }
        }

        var anotherThing: MyThing? by xdLink0_1(MyThing::thisThing);
        var thisThing by xdLink0_1(MyThing::anotherThing);
        var ssss by xdStringProp()
    }


    class MyThing2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<MyThing2>() {
            fun new(n: String) = new { this.n = n }
        }

        val children: XdMutableQuery<MyThing2> by xdLink0_N(MyThing2::parents)
        val parents by xdLink0_N(MyThing2::children)
        var n by xdStringProp()
    }

    class MyThing3(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<MyThing3>()

        val tome1 by xdLink0_N(MyThing3::tome2)
        var tome2: MyThing3? by xdLink0_1(MyThing3::tome1)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(MyThing, MyThing2, MyThing3)
    }

    @Test
    fun test1() {
        val (m1, m2) = transactional {
            val m1 = MyThing.new()
            val m2 = MyThing.new()
            m1.thisThing = m2
            assertThat(m1.thisThing).isEqualTo(m2)
            assertThat(m2.anotherThing).isEqualTo(m1)
            Pair(m1, m2)
        }

        transactional {
            assertThat(m1.thisThing).isEqualTo(m2)
            assertThat(m2.anotherThing).isEqualTo(m1)
        }
    }

    @Test
    fun test2() {
        val (t1, t2, t3) = transactional {
            val t1 = MyThing2.new("1")
            val t2 = MyThing2.new("2")
            val t3 = MyThing2.new("3")
            t1.children.add(t2)
            t3.parents.add(t2)

            assertThat(t1.children.first()).isEqualTo(t2)
            assertThat(t2.parents.first()).isEqualTo(t1)
            assertThat(t2.children.first()).isEqualTo(t3)
            assertThat(t3.parents.first()).isEqualTo(t2)
            Triple(t1, t2, t3)
        }
        transactional {
            assertThat(t1.children.first()).isEqualTo(t2)
            assertThat(t2.parents.first()).isEqualTo(t1)
            assertThat(t2.children.first()).isEqualTo(t3)
            assertThat(t3.parents.first()).isEqualTo(t2)
        }
    }

    @Test
    fun test3() {
        transactional {
            val m = MyThing3.new()
            val m2 = MyThing3.new()
            m.tome2 = m2
            m.tome1.add(m2)

            assertThat(m.tome2).isEqualTo(m2)
            assertThat(m2.tome2).isEqualTo(m)
            assertThat(m.tome1.first()).isEqualTo(m2)
        }
        transactional {
            val (m, m2) = MyThing3.all().toList()
            assertThat(m.tome2).isEqualTo(m2)
            assertThat(m2.tome2).isEqualTo(m)
        }
    }

    @Test
    fun test4() {
        val m = transactional {
            MyThing3.new {
                tome2 = MyThing3.new()
            }
        }
        transactional {
            m.tome2 = null
        }
        transactional {
            assertThat(m.tome2).isNull()
        }
    }

    @Test
    fun test_WD_2065_1() {
        val (t1, t2) = transactional {
            Pair(MyThing3.new(), MyThing3.new())
        }
        transactional {
            t1.tome2 = t2
            assertThat(t2.tome1).containsExactly(t1)
            transactional(isNew = true) {
                t2.delete()
            }
        }
        transactional {
            assertThat(t2.isRemoved).isTrue()
            assertThat(t1.tome2).isNull()
        }
    }

}
