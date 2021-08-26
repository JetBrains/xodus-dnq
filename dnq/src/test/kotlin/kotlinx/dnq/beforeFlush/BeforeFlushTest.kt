/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package kotlinx.dnq.beforeFlush


import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import org.junit.Test

class BeforeFlushTest : DBTest() {
    class Car(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Car>()

        var tire by xdLink0_1(Tire)
        var name by xdStringProp()


        override fun beforeFlush() {
            tire?.rotate()
        }
    }

    class Tire(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Tire>()

        var rotation by xdIntProp()
        var rotating by xdBooleanProp()
        var pin by xdLink0_1(Pin)

        override fun constructor() {
            rotation = 0;
            rotating = false;
        }

        override fun beforeFlush() {
            if (rotating) {
                rotation += 1;
                rotating = false;
            }
            pin?.screw();
        }

        fun rotate() {
            println("rotate");
            rotating = true;
        }
    }

    class Pin(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Pin>()

        var screwed by xdIntProp()
        var screwing by xdBooleanProp()

        override fun constructor() {
            screwed = 0;
            screwing = false;
        }

        override fun beforeFlush() {
            if (screwing) {
                screwed += 1;
                screwing = false;
            }
        }

        fun screw() {
            screwing = true;
        }
    }


    override fun registerEntityTypes() {
        XdModel.registerNodes(Car, Tire, Pin)
    }

    @Test
    fun beforeFlushIsExecutedForNewEntity() {
        store.transactional { txn ->
            val pin = Pin.new()
            pin.screw()
            txn.flush()
            assertThat(pin.screwed).isEqualTo(1)
        }
    }

    @Test
    fun beforeFlushIsExecutedForSavedEntity() {
        store.transactional { txn ->
            val pin = Pin.new()
            txn.flush()
            assertThat(pin.screwed).isEqualTo(0)
            pin.screw()
            txn.flush()
            assertThat(pin.screwed).isEqualTo(1)
        }
    }

    @Test
    fun sideEffectsFromListener() {
        store.transactional { txn ->
            val tire = Tire.new()
            txn.flush()
            store.changesMultiplexer?.addListener(tire, object : XdEntityListener<Tire> {
                override fun updatedSyncBeforeConstraints(old: Tire, current: Tire) {
                    // create a car for our tire, expect that car before flush is called and tire is rotated
                    val car = Car.new()
                    car.tire = tire
                }
            })
            assertThat(tire.rotating).isFalse()
            tire.rotation = 666
            txn.flush()
            // this is creepy, but tire before flush shouldn't be called second time
            assertThat(tire.rotating).isTrue()
        }
    }

    @Test
    fun beforeFlushWithSideEffectListener() {
        var gotIt = false
        store.transactional { txn ->
            val car = Car.new()
            val tire = Tire.new()
            car.tire = tire
            txn.flush()

            tire.rotating = false
            txn.flush()

            assertThat(tire.rotating).isFalse()
            car.name = "mmc"
            store.changesMultiplexer?.addListener(tire, object : XdEntityListener<Tire> {
                override fun updatedSync(old: Tire, current: Tire) {
                    gotIt = true
                }
            })
            assertThat(gotIt).isFalse()
            txn.flush()
            assertThat(gotIt).isTrue()
        }
    }

    @Test
    fun beforeFlushWithSideEffects() {
        store.transactional { txn ->
            val pin = Pin.new()
            val tire = Tire.new { this.pin = pin }
            val car = Car.new { this.tire = tire }
            txn.flush()

            tire.rotating = false
            txn.flush()

            pin.screwing = false
            txn.flush()

            assertThat(tire.rotating).isFalse()
            assertThat(pin.screwing).isFalse()

            val rotation = tire.rotation
            val screwed = pin.screwed
            car.name = "mmc"
            txn.flush()

            assertThat(tire.rotation).isEqualTo(rotation + 1)
            assertThat(pin.screwed).isEqualTo(screwed + 1)
            car.name = "mb"
            txn.flush()

            assertThat(tire.rotation).isEqualTo(rotation + 2)
            assertThat(pin.screwed).isEqualTo(screwed + 2)
        }
    }
}
