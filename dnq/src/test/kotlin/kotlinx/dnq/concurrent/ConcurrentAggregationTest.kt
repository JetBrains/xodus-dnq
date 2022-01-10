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
package kotlinx.dnq.concurrent

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.junit.Test

class ConcurrentAggregationTest : DBTest() {

    class Car(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Car>()

        var engine by xdChild0_1(Engine::car)
        val wheels by xdChildren0_N(Wheel::car)
    }

    class Engine(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Engine>()

        var car: Car by xdParent(Car::engine)
    }

    class Wheel(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Wheel>()

        var car: Car by xdParent(Car::wheels)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Car, Engine, Wheel)
    }

    @Test
    fun setOneToOne() {
        val focus = transactional { Car.new() }
        val c4 = transactional { Car.new() }
        val engine1 = transactional {
            val engine1 = Engine.new()
            focus.engine = engine1
            engine1
        }
        val engine2 = transactional {
            val engine2 = Engine.new()
            focus.engine = engine2

            store.runTranAsyncAndJoin {
                c4.engine = engine1
            }
            engine2
        }
        transactional {
            assertThat(c4.engine).isEqualTo(engine1)
            assertThat(focus.engine).isEqualTo(engine2)
        }
    }

    @Test
    fun setManyToOne() {
        val focus = transactional { Car.new() }
        val c4 = transactional { Car.new() }
        val astra = transactional { Car.new() }
        val wheel1 = transactional {
            val wheel1 = Wheel.new()
            focus.wheels.add(wheel1)
            wheel1
        }
        transactional {
            c4.wheels.add(wheel1)
            store.runTranAsyncAndJoin {
                astra.wheels.add(wheel1)
            }
        }
        transactional {
            assertQuery(c4.wheels).containsExactly(wheel1)
            assertQuery(astra.wheels).isEmpty()
            assertQuery(focus.wheels).isEmpty()
        }
    }

    @Test
    fun setManyToOne2() {
        val focus = transactional { txn ->
            val focus = Car.new()
            focus.wheels.add(Wheel.new())
            focus.wheels.add(Wheel.new())
            txn.flush()

            focus.wheels.add(Wheel.new())
            store.runTranAsyncAndJoin {
                focus.wheels.add(Wheel.new())
            }
            focus
        }
        transactional {
            assertQuery(focus.wheels).hasSize(4)
        }
    }
}