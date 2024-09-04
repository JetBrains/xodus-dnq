/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
package kotlinx.dnq.query

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class EnumTest : DBTest() {


    abstract class Vehicle(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Vehicle>()
        var engine by xdLink0_1(Engine)
        var body by xdLink0_1(Body)
    }

    class Car(entity: Entity) : Vehicle(entity) {
        companion object : XdNaturalEntityType<Car>()

        var name by xdRequiredStringProp()

    }

    class Engine(entity: Entity) : XdEnumEntity(entity) {

        companion object : XdEnumEntityType<Engine>() {
            val DIESEL by Engine.enumField {}
            val GASOLINE by Engine.enumField {}
            val MICROWAVE by Engine.enumField {}
        }
    }

    class Body(entity: Entity) : XdEnumEntity(entity) {
        companion object : XdEnumEntityType<Body>() {
            val COUPE by Body.enumField {}
            val SALOON by Body.enumField {}
            val PICKUP by Body.enumField {}
        }
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Car, Engine, Body, Vehicle)
    }


    @Before
    fun init() {
        transactional {
            Car.new {
                name = "pick1"
                engine = Engine.DIESEL
                body = Body.PICKUP
            }
            Car.new {
                name = "pick2"
                engine = Engine.GASOLINE
                body = Body.PICKUP
            }
            Car.new {
                name = "coupe1"
                engine = Engine.DIESEL
                body = Body.COUPE
            }
            Car.new {
                name = "coupe2"
                engine = Engine.GASOLINE
                body = Body.COUPE
            }
            Car.new {
                name = "grill"
                engine = Engine.MICROWAVE
                body = Body.SALOON
            }
        }
    }


    @Test
    fun andQueryWith2Enums() {
        val diesel = transactional {
            Car.filter {
                ((it.body eq Body.COUPE) and (it.engine eq Engine.DIESEL))
            }.toList().firstOrNull()
        }
        transactional {
            Assert.assertNotNull(diesel)
            Assert.assertEquals("coupe1", diesel?.name)
        }
    }


}