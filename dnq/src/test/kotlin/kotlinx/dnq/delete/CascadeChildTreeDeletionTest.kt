/**
 * Copyright 2006 - 2022 JetBrains s.r.o.
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
package kotlinx.dnq.delete


import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.query.size
import org.junit.Test


class CascadeChildTreeDeletionTest : DBTest() {
    class A1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<A1>()

        val b by xdChildren0_N(B1::a)
    }

    class B1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<B1>()

        var a: A1 by xdParent(A1::b)
        val c by xdChildren0_N(C1::b)
    }

    class C1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<C1>()

        var b: B1 by xdParent(B1::c)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(A1, B1, C1)
    }

    @Test
    fun descendingDeleteion() {
        val b = transactional {
            val a = A1.new()
            val c = C1.new()
            B1.new().also { b ->
                a.b.add(b)
                a.b.add(B1.new())
                b.c.add(c)
                b.c.add(C1.new())
            }
        }
        transactional {
            b.delete()
        }
        transactional {
            System.out.println("A1 :    O    : " + A1.all().size())
            System.out.println("B1 :  X   O  : " + B1.all().size())
            System.out.println("C1 : O O     : " + C1.all().size())
            assertQuery(B1.all()).hasSize(1)
            assertQuery(C1.all()).isEmpty()
        }
    }
}
