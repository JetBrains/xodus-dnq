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
package kotlinx.dnq.concurrent.transaction


import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.junit.Test
import kotlin.concurrent.thread

class TinyConcurrentStressTest : DBTest() {

    class Something(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Something>()

        var i1 by xdIntProp()
        var i2 by xdIntProp()
    }


    override fun registerEntityTypes() {
        XdModel.registerNode(Something)
    }

    @Test
    fun t() {
        val s = transactional {
            Something.new()
        }

        val testDuration = 10000
        val threadCount = 3
        (0 until threadCount)
                .map {
                    thread {
                        val finishAt = System.currentTimeMillis() + testDuration
                        do {
                            transactional { txn ->
                                s.i1 = Any().hashCode()
                                txn.flush()
                                transactional(isNew = true) {
                                    s.i2 = Any().hashCode()
                                }
                            }
                        } while (System.currentTimeMillis() < finishAt)
                    }
                }
                .forEach(Thread::join)
    }
}
