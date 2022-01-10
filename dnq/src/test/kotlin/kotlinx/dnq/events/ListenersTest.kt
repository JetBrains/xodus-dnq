/**
 * Copyright 2006 - 2022 JetBrains s.r.o.
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
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ListenersTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(Foo)
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

}