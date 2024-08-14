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
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import kotlinx.dnq.query.asSequence
import kotlinx.dnq.query.size
import org.junit.Ignore
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
                ref.set(removed.intField)
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
        Truth.assertThat(ref.get()).isEqualTo(99)
    }

    @Test
    @Ignore
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
               content.add(Foo.new().apply {
                   intField = 99
               })
               content.add(Foo.new().apply {
                   intField = 99
               })
               content.add(Foo.new().apply {
                   intField = 99
               })
               content.add(Foo.new().apply {
                   intField = 99
               })
            }
        }
        store.transactional {
            goo.delete()
        }
        Truth.assertThat(failedInWriteInOnRemoveHandler).isTrue()
        Truth.assertThat(ref.get()).isEqualTo(4)
        Truth.assertThat(
            store.transactional {
                Foo.all().asSequence().map { it.intField }.all { it == 99 }
            }

        ).isTrue()
    }




}
