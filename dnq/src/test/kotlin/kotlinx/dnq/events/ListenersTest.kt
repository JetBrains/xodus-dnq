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
package kotlinx.dnq.events

import com.google.common.truth.Truth
import jetbrains.exodus.entitystore.EntityId
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import kotlinx.dnq.query.asSequence
import kotlinx.dnq.query.filter
import kotlinx.dnq.query.size
import kotlinx.dnq.query.toList
import kotlinx.dnq.util.getAddedLinks
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ListenersTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(Foo, Goo)
    }

    private val ref = AtomicInteger(0)

    @Test
    fun addSyncBeforeConstraint() {
        Foo.addListener(store, object : XdEntityListener<Foo> {
            override fun addedSyncBeforeConstraints(added: Foo) {
                ref.set(1)
            }
        })

        store.transactional {
            Foo.new()
        }
        store.transactional {
            Truth.assertThat(ref.get()).isEqualTo(1)
        }
    }

    @Test
    fun updatePropertyTest(){
        val foo = store.transactional {
            Foo.new().apply { intField = 12 }
        }
        Foo.addListener(store, object : XdEntityListener<Foo> {
            override fun updatedSync(old: Foo, current: Foo) {
                ref.set(old.intField)
            }
        })
        store.transactional {
            foo.intField = 99
        }
        Assert.assertEquals(12, ref.get())
    }

    @Test
    fun updatePropertyMultipleTimeTest(){
        val foo = store.transactional {
            Foo.new().apply { intField = 12 }
        }
        Foo.addListener(store, object : XdEntityListener<Foo> {
            override fun updatedSync(old: Foo, current: Foo) {
                ref.set(old.intField)
            }
        })
        store.transactional {
            foo.intField = 99
            foo.intField = 100
            foo.intField = 101
        }
        Assert.assertEquals(12, ref.get())
    }

    @Test
    fun updatePropertyMultipleTimeAndGetBackToDefault(){
        val foo = store.transactional {
            Foo.new().apply { intField = 12 }
        }
        Foo.addListener(store, object : XdEntityListener<Foo> {
            override fun updatedSync(old: Foo, current: Foo) {
                ref.set(old.intField)
            }
        })
        store.transactional {
            foo.intField = 99
            foo.intField = 91
            foo.intField = 12
            foo.intField = 12
            foo.intField = 91
            foo.intField = 12
        }
        Assert.assertEquals(12, ref.get())
    }


    @Test
    fun removedSyncBeforeConstraint() {
        Foo.addListener(store, object : XdEntityListener<Foo> {
            override fun removedSyncBeforeConstraints(removed: Foo) {
                ref.set(2)
            }
        })

        val foo = store.transactional {
            Foo.new()
        }
        store.transactional {
            foo.delete()
        }
        store.transactional {
            Truth.assertThat(ref.get()).isEqualTo(2)
        }
    }

    @Test
    fun updatedSyncBeforeConstraint() {
        Foo.addListener(store, object : XdEntityListener<Foo> {
            override fun updatedSyncBeforeConstraints(old: Foo, current: Foo) {
                ref.set(3)
            }
        })

        val foo = store.transactional {
            Foo.new()
        }
        store.transactional {
            foo.intField = 10
        }
        store.transactional {
            Truth.assertThat(ref.get()).isEqualTo(3)
        }
    }

    @Test
    fun removedTest() {
        Foo.addListener(store, object : XdEntityListener<Foo> {
            override fun removedSync(removed: Foo) {
                ref.set(239)
            }
        })

        val foo = store.transactional {
            Foo.new().apply {
                intField = 99
            }
        }
        store.transactional {
            foo.delete()
        }
        Truth.assertThat(ref.get()).isEqualTo(239)
    }

    @Test
    fun removedTestWithLinksTest() {
        var failedInWriteInOnRemoveHandler = false
        Goo.addListener(store, object : XdEntityListener<Goo> {
            override fun removedSync(removed: Goo) {
                removed.content.asSequence().forEach {
                    try {
                        it.intField = 11
                    } catch (_:Throwable) {
                        failedInWriteInOnRemoveHandler = true
                    }
                }
                ref.set(removed.content.size())
            }
        })

        val goo = store.transactional {
            Goo.new().apply {
                repeat(4) {
                    content.add(Foo.new().apply {
                        intField = 99
                    })
                }
            }
        }
        store.transactional {
            goo.delete()
        }
        Truth.assertThat(failedInWriteInOnRemoveHandler).isTrue()
        Truth.assertThat(ref.get()).isEqualTo(4)
        Truth.assertThat(
            store.transactional {
                Foo.all().toList().map { it.intField }.all { it == 99 }
            }

        ).isTrue()
    }


    @Test
    fun removedTransientEntityEqualsToPrototype() {
        val data = hashMapOf<Foo, Int>()
        Foo.addListener(store, object : XdEntityListener<Foo> {
            override fun removedSyncBeforeConstraints(removed: Foo) {
                data.remove(removed)
            }
        })


        val foo = transactional {
            val foo = Foo.new()
            data[foo] = 99
            foo
        }
        transactional {
            foo.delete()
        }
        Assert.assertEquals(0, data.size)
    }

    @Test
    fun removeLinksTest() {
        val refOld = AtomicInteger(-1)
        val refNew = AtomicInteger(-1)
        Goo.addListener(store, object : XdEntityListener<Goo> {
            override fun updatedSync(old: Goo, current: Goo) {
                refOld.set(old.content.size())
                refNew.set(current.content.size())
            }
        })
        val g = store.transactional {
            Goo.new()
        }
        store.transactional {
            g.content.add(Foo.new())
            g.content.add(Foo.new())
        }

        Assert.assertEquals(2, refNew.get())
        Assert.assertEquals(0, refOld.get())
        store.transactional {
            g.content.clear()
        }
        Assert.assertEquals(0, refNew.get())
        Assert.assertEquals(2, refOld.get())
    }

    /**
     * Reproducer: filtering added links inside an updatedSync listener crashes with
     * UnsupportedOperationException because TransientEntityIterable.intersect() is not supported.
     *
     * getAddedLinks() returns a TransientEntityIterable wrapped in XdQuery.
     * Calling .filter {} invokes QueryEngine.query(instance=TransientEntityIterable, ...),
     * which enters the `instance is EntityIterable` branch and calls instance.intersect() — crash.
     */
    @Test
    fun `filter added links in updatedSync listener should not crash`() {
        val g = store.transactional {
            Goo.new()
        }
        val result = AtomicReference<List<Int>>()
        val error = AtomicReference<Throwable>()
        Goo.addListener(store, object : XdEntityListener<Goo> {
            override fun updatedSync(old: Goo, current: Goo) {
                try {
                    val filtered = old.getAddedLinks(Goo::content)
                        .filter { it.intField eq 2 }
                        .toList()
                    result.set(filtered.map { it.intField })
                } catch (e: Throwable) {
                    error.set(e)
                }
            }
        })
        store.transactional {
            g.content.add(Foo.new { intField = 1 })
            g.content.add(Foo.new { intField = 2 })
            g.content.add(Foo.new { intField = 3 })
        }
        if (error.get() != null) {
            throw AssertionError(
                "Filtering TransientEntityIterable should not crash", error.get()
            )
        }
        Truth.assertThat(result.get()).containsExactly(2)
    }

    @Test
    fun multipleObjectsRemovedInSingleTransaction(){
        val idToValue = hashMapOf<EntityId, Int>()
        val idToValueProof = hashMapOf<EntityId, Int>()
        Foo.addListener(store, object :XdEntityListener<Foo>{

            override fun removedSync(removed: Foo) {
                idToValueProof[removed.entityId] = removed.intField
            }
        })
        transactional {
            (0..100).forEach {
                Foo.new {
                    intField = it
                }
            }
        }
        transactional {
            Foo.all().toList().forEach { foo->
                idToValue[foo.entityId] = foo.intField
            }
        }
        transactional {
            val list = Foo.all().toList().toMutableList().apply {
                shuffle()
            }
            list.forEach { it.delete() }
        }
        Assert.assertEquals(idToValue, idToValueProof)
    }
}
