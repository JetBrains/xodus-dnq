/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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

import com.google.common.truth.IterableSubject
import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import org.junit.Test
import java.util.*
import kotlin.collections.HashSet

class ToKotlinCollectionTest : DBTest() {

    class Thing(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Thing>()

        override fun toString() = entity.id.localId.toString()
    }

    override fun registerEntityTypes() {
        XdModel.registerNode(Thing)
    }

    @Test
    fun asIterable() {
        assertToCollectionOperation {
            assertThat(it.asIterable())
        }
    }

    @Test
    fun iterator() {
        assertToCollectionOperation {
            assertThat(it.iterator().asSequence().toList())
        }
    }

    @Test
    fun toCollection() {
        assertToCollectionOperation {
            val destination = LinkedList<Thing>()
            assertThat(it.toCollection(destination)).apply {
                isSameAs(destination)
            }
        }
    }

    @Test
    fun toList() {
        assertToCollectionOperation {
            assertThat(it.toList()).apply {
                isInstanceOf(List::class.java)
            }
        }
    }

    @Test
    fun toMutableList() {
        assertToCollectionOperation {
            assertThat(it.toMutableList()).apply {
                isInstanceOf(MutableList::class.java)
            }
        }
    }

    @Test
    fun toSet() {
        assertToCollectionOperation {
            assertThat(it.toSet()).apply {
                isInstanceOf(Set::class.java)
            }
        }
    }

    @Test
    fun toHashSet() {
        assertToCollectionOperation {
            assertThat(it.toHashSet()).apply {
                isInstanceOf(HashSet::class.java)
            }
        }
    }

    @Test
    fun toSortedSet() {
        assertToCollectionOperation {
            assertThat(it.toSortedSet(compareBy { it.entityId })).apply {
                isInstanceOf(SortedSet::class.java)
            }
        }
    }

    @Test
    fun toMutableSet() {
        assertToCollectionOperation {
            assertThat(it.toMutableSet()).apply {
                isInstanceOf(MutableSet::class.java)
            }
        }
    }

    private fun assertToCollectionOperation(toCollection: (XdQuery<Thing>) -> IterableSubject) {
        val things = transactional {
            (1..10).map {
                Thing.new()
            }
        }
        transactional {
            toCollection(Thing.all()).containsExactlyElementsIn(things)
        }
    }
}