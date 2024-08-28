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
package kotlinx.dnq.concurrent


import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.junit.Ignore
import org.junit.Test

class DirectedAssociationTest : DBTest() {

    class Phone(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Phone>()

        var owner by xdLink0_1(PhoneOwner)
        val buttons by xdLink0_N(PhoneButton)
    }

    class PhoneButton(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PhoneButton>()
    }

    class PhoneOwner(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PhoneOwner>()
    }


    override fun registerEntityTypes() {
        XdModel.registerNodes(Phone, PhoneButton, PhoneOwner)
    }

    @Test
    
    fun setToOne() {
        val phone = transactional { txn ->
            val phone = Phone.new()
            val pavleg = PhoneOwner.new()
            val vadeg = PhoneOwner.new()

            phone.owner = pavleg
            txn.flush()

            phone.owner = null

            store.runTranAsyncAndJoin {
                phone.owner = vadeg
            }

            phone
        }
        transactional {
            assertThat(phone.owner).isNull()
        }
    }

    @Test
    @Ignore
    fun createToMany() {
        val phone = transactional { txn ->
            val phone = Phone.new()
            phone.buttons.add(PhoneButton.new())
            phone.buttons.add(PhoneButton.new())
            txn.flush()

            phone.buttons.add(PhoneButton.new())

            store.runTranAsyncAndJoin {
                phone.buttons.add(PhoneButton.new())
            }
            phone
        }

        transactional {
            assertQuery(phone.buttons).hasSize(4)
        }
    }

    @Test
    
    fun clearToMany() {
        val phone = transactional { txn ->
            val phone = Phone.new()
            phone.buttons.add(PhoneButton.new())
            phone.buttons.add(PhoneButton.new())
            txn.flush()

            phone.buttons.clear()
            store.runTranAsyncAndJoin {
                phone.buttons.add(PhoneButton.new())
            }
            phone
        }
        transactional {
            phone.buttons.clear()
        }
    }
}
