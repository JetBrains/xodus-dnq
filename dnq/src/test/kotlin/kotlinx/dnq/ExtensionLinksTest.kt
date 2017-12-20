/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.ExtensionLinksTest.Person
import kotlinx.dnq.ExtensionLinksTest.Spy
import kotlinx.dnq.util.getDBName
import org.junit.Test

class ExtensionLinksTest : DBTest() {

    class Person(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Person>()

        var name by xdStringProp()
    }

    class Spy(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Spy>()

        val informant by xdLink1_N(Person::curator, dbOppositePropertyName = "_curator_", dbPropertyName = "_informant_")
    }

    override fun registerEntityTypes() {
        //order is necessary
        XdModel.registerNode(Spy)
        XdModel.registerNode(Person)
    }

    @Test
    fun `setters should work`() {
        val person = store.transactional {
            Person.new {
                name = "Rozenberg"
            }
        }
        store.transactional {
            val spy = Spy.new {
                informant.add(person)
            }
            assertThat(person.curator).isEqualTo(spy)
        }
    }

    @Test(expected = ConstraintsValidationException::class)
    fun `constraints should work`() {
        store.transactional {
            Spy.new()
        }
    }


    @Test
    fun `extension links should be pushed to metadata`() {
        assertThat(Spy::informant.getDBName()).isEqualTo("_informant_")
        assertThat(Person::curator.getDBName()).isEqualTo("_curator_")
    }
}


var Person.curator: Spy? by xdLink0_1(Spy::informant, dbPropertyName = "_curator_", dbOppositePropertyName = "_informant_")
