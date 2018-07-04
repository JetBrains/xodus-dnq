/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package kotlinx.dnq.concurrent.transaction


import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.query.first
import kotlinx.dnq.query.size
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

class SnapshotIsolationTest : DBTest() {

    class Something(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Something>()

        var sometext by xdStringProp()
        var i1 by xdIntProp()
        var i2 by xdIntProp()
    }

    private var count1 = -1
    private var count2 = -2
    private var ex: Exception? = null

    @Before
    fun createSomething() {
        transactional {
            for (i in 0..9) {
                Something.new {
                    sometext = "s$i"
                    i1 = i
                    i2 = i
                }
            }
        }
    }

    override fun registerEntityTypes() {
        XdModel.registerNode(Something)
    }

    @Test
    fun cursorSizeRepeatableRead() {
        // plan:
        // 1. start 2 concurrent transations
        // 2. make query in first tran
        // 3. change query data in second tran
        // 4. read query again in first tran and compare data with read on step 2

        store.runTranAsyncAndJoin {
            count1 = Something.all().size()

            store.runTranAsyncAndJoin {
                Something.new { sometext = "extra" }
            }

            count2 = Something.all().size()
        }

        assertThat(count2).isEqualTo(count1)
    }

    @Test
    fun cursorSizeRepeatableRead2() {
        store.runTranAsyncAndJoin(readonly = true) {
            count1 = Something.all().size()

            store.runTranAsyncAndJoin {
                Something.new { sometext = "extra" }
            }

            count2 = Something.all().size()
        }

        assertThat(count2).isEqualTo(count1)
    }

    @Test
    fun entityPropRepeatableRead() {
        // plan:
        // 1. start 2 concurrent transations
        // 2. make query in first tran
        // 3. change query data in second tran
        // 4. read query again in first tran and compare data with read on step 2

        store.runTranAsyncAndJoin {
            count1 = Something.all().first().i1

            store.runTranAsyncAndJoin {
                Something.all().first().i2++
            }

            count2 = Something.all().first().i2
        }

        assertThat(count2).isEqualTo(count1)
    }

    @Test
    fun entityPropRepeatableRead2() {
        store.runTranAsyncAndJoin(readonly = true) {
            count1 = Something.all().first().i1

            store.runTranAsyncAndJoin {
                Something.all().first().i2++
            }

            count2 = Something.all().first().i2
        }

        assertThat(count2).isEqualTo(count1)
    }

    @Test
    fun handleLowLevelVersionMismatch() {
        store.runTranAsyncAndJoin { txn ->
            Something.new { sometext = "new1" }

            store.runTranAsyncAndJoin {
                Something.new { sometext = "new2" }
            }

            try {
                txn.flush()
            } catch (e: Exception) {
                ex = e
            }

        }

        assertThat(ex).isNull()
    }

    @Test
    fun concurrentFlushPerformance() {
        val k = Runtime.getRuntime().availableProcessors()
        val s = 10
        val duration = measureTimeMillis {
            (0 until k)
                    .map { idx ->
                        thread(name = "Thread $idx", start = true) {
                            transactional { txn ->
                                for (j in 1..s) {
                                    Something.new { sometext = "something${Math.random()}" }
                                    try {
                                        txn.flush()
                                        if (j % 10 == 0) {
                                            println("${Thread.currentThread().name} flushed $j")
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        ex = e
                                    }

                                }
                            }
                        }
                    }
                    .forEach(Thread::join)
        }


        println("Speed ${k * s / duration.toFloat() * 1000}")
    }
}
