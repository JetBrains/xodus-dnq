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
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.toList
import kotlinx.dnq.singleton.XdSingletonEntityType
import org.junit.Test

class SingletonTest : DBTest() {

    class TheKing(override val entity: Entity) : XdEntity() {
        companion object : XdSingletonEntityType<TheKing>() {

            override fun TheKing.initSingleton() {
                name = "Elvis"
            }
        }

        var name by xdRequiredStringProp()
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(TheKing)
    }

    @Test
    fun `singleton should be alone`() {
        val (first, second) = store.transactional {
            val first = TheKing.get()
            val second = store.transactional(isNew = true) {
                TheKing.get()
            }
            Pair(first, second)
        }

        store.transactional {
            assertThat(first).isEqualTo(second)
        }
    }

    @Test
    fun `number of singletons should always be one`() {
        store.transactional {
            assertThat(TheKing.all().toList())
                    .containsExactly(TheKing.get())
        }
    }
}