/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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

    class Sibling(entity: Entity): Parent(entity) {
        companion object : XdNaturalEntityType<Sibling>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Parent, Child, Sibling)
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

    // The receiver query's runtime entityType is a *sibling* of the filter target (not an
    // ancestor), while the iterable actually holds target-type entities. This happens when an
    // iterable of one type is wrapped as a query of an unrelated type and then narrowed with
    // filterIsInstance back to the type it actually contains.
    @Test
    fun `filter instance from sibling-labeled query`() {
        transactional {
            Child.new()
            Child.new()
        }

        transactional {
            // Two Child entities, but the query is labeled as the sibling type Sibling.
            val siblingLabeled: XdQuery<Parent> = Child.all().entityIterable.asQuery(Sibling)
            assertQuery(siblingLabeled.filterIsInstance(Child)).hasSize(2)
        }
    }

    @Test
    fun `filter not-instance from sibling-labeled query`() {
        transactional {
            Child.new()
            Child.new()
        }

        transactional {
            // Two Child entities labeled as the sibling type Sibling; none are actually Sibling.
            val siblingLabeled: XdQuery<Parent> = Child.all().entityIterable.asQuery(Sibling)
            assertQuery(siblingLabeled.filterIsNotInstance(Sibling)).hasSize(2)
        }
    }
}
