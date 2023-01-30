/**
 * Copyright 2006 - 2023 JetBrains s.r.o.
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


import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
import org.junit.Ignore
import org.junit.Test

class OrphansAutoRemoveTest : DBTest() {

    class Cell(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Cell>()

        var parentCell: Cell by xdParent(Cell::children)
        val children by xdChildren0_N(Cell::parentCell)

        fun test() = Unit
    }


    class D0(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<D0>()

        var d1 by xdChild0_1(D1::d0)
    }


    class D1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<D1>()

        var d0: D0 by xdParent(D0::d1)
    }

    class D2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<D2>()

        var d1 by xdLink0_1(D1, onTargetDelete = CLEAR)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Cell, D0, D1, D2)
    }

    @Test
    fun orphanWithoutChildrenAutoremove() {
        transactional {
            Cell.new()
        }
        transactional {
            assertQuery(Cell.all()).isEmpty()
        }
    }

    @Test
    fun orphanWithChildrenAutoremoveCascade() {
        transactional {
            // cell with null parent
            Cell.new {
                children.add(Cell.new())
                children.add(Cell.new())
            }
            // cell should be deleted as orphan
            // children will be deleted successively as orphans
        }
        transactional {
            assertQuery(Cell.all()).isEmpty()
        }
    }

    @Test
    @Ignore
    fun linkToOrphanIsCleared() {
        val (d0, d1, d2) = transactional {
            val d1 = D1.new()
            val d0 = D0.new { this.d1 = d1 }
            val d2 = D2.new { this.d1 = d1 }
            Triple(d0, d1, d2)
        }
        transactional {
            d0.d1 = D1.new()
        }
        transactional {
            assertThat(d1.isRemoved).isTrue()
            assertThat(d2.isRemoved).isFalse()
            assertThat(d2.d1).isNull()
        }
    }
}
