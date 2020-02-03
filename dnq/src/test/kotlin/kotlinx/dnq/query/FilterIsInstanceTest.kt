/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
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
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import org.junit.Test

class FilterIsInstanceTest : DBTest() {

    open class Parent(entity: Entity): XdEntity(entity) {
        companion object : XdNaturalEntityType<Parent>()
    }

    class Child(entity: Entity): Parent(entity) {
        companion object : XdNaturalEntityType<Child>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Parent, Child)
    }

    @Test
    fun `filter children`() {
        transactional {
            Parent.new()
            Child.new()
            Child.new()
        }

        transactional {
            assertQuery(Parent.all().filterIsInstance(Child)).hasSize(2)
        }
    }

    @Test
    fun `filter not children`() {
        transactional {
            Parent.new()
            Child.new()
            Child.new()
        }

        transactional {
            assertQuery(Parent.all().filterIsNotInstance(Child)).hasSize(1)
        }
    }

    @Test
    fun `filter parent`() {
        transactional {
            Parent.new()
            Child.new()
            Child.new()
        }

        transactional {
            assertQuery(Parent.all().filterIsInstance(Parent)).hasSize(3)
        }
    }

    @Test
    fun `filter not parent`() {
        transactional {
            Parent.new()
            Child.new()
            Child.new()
        }

        transactional {
            assertQuery(Parent.all().filterIsNotInstance(Parent)).isEmpty()
        }
    }
}