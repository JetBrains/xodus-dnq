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
package kotlinx.dnq.events

import com.google.common.truth.Truth
import jetbrains.exodus.database.RemovedEntityData
import jetbrains.exodus.entitystore.EntityId
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import kotlinx.dnq.query.asSequence
import kotlinx.dnq.query.size
import kotlinx.dnq.query.toList
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
    fun removedSyncBeforeConstraint() {
        Foo.addListener(store, object : XdEntityListener<Foo> {
            override fun removedSyncBeforeConstraints(removed: Foo, removedEntityData: RemovedEntityData<Foo>) {
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
            override fun removedSync(removedEntityData: RemovedEntityData<Foo>) {
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
            override fun removedSync(removedEntityData: RemovedEntityData<Goo>) {
                try {
                    removedEntityData.removed.content.clear()
                } catch (_: Throwable) {
                    failedInWriteInOnRemoveHandler = true
                }
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
        Truth.assertThat(ref.get()).isEqualTo(0)
        Truth.assertThat(
            store.transactional {
                Foo.all().asSequence().map { it.intField }.all { it == 99 }
            }

        ).isTrue()
    }


    @Test
    fun removedTransientEntityEqualsToPrototype() {
        val data = hashMapOf<Foo, Int>()
        Foo.addListener(store, object : XdEntityListener<Foo> {
            override fun removedSyncBeforeConstraints(removed: Foo, removedEntityData: RemovedEntityData<Foo>) {
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

    @Test
    fun removedTestWithLinksTestWithStore() {
        Goo.addListener(store, object : XdEntityListener<Goo> {
            override fun removedSyncBeforeConstraints(removed: Goo, removedEntityData: RemovedEntityData<Goo>) {
                val links = removed.content.toList()
                removedEntityData.storeValue("list", links)
            }

            override fun removedSync(removedEntityData: RemovedEntityData<Goo>) {
                val list: List<Foo> = removedEntityData.getValue("list") ?: throw NoSuchElementException()
                list.forEach {
                    it.intField = 11
                }
                ref.set(list.size)
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

        Truth.assertThat(ref.get()).isEqualTo(4)
        Truth.assertThat(
            store.transactional {
                Foo.all().asSequence().map { it.intField }.all { it == 11 }
            }

        ).isTrue()
    }

    @Test
    fun multipleObjectsRemovedInSingleTransaction(){
        val idToValue = hashMapOf<EntityId, Int>()
        val idToValueProof = hashMapOf<EntityId, Int>()
        Foo.addListener(store, object :XdEntityListener<Foo>{
            override fun removedSyncBeforeConstraints(removed: Foo, removedEntityData: RemovedEntityData<Foo>) {
                removedEntityData.storeValue(Foo::intField, removed.intField)
            }

            override fun removedSync(removedEntityData: RemovedEntityData<Foo>) {
                idToValueProof[removedEntityData.removedId] = removedEntityData.getValue(Foo::intField)!!
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
            Foo.all().asSequence().forEach { foo->
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
