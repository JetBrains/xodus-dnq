/**
 * Copyright 2006 - 2023 JetBrains s.r.o.
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


import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.query.asSequence
import kotlinx.dnq.query.query
import kotlinx.dnq.query.size
import kotlinx.dnq.query.startsWith
import kotlinx.dnq.util.findById
import org.junit.Test
import java.lang.Integer.max
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class TransactionsIsolationTest : DBTest() {
    class Something(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Something>() {
            fun new(text: String) = Something.new {
                this.sometext = text
            }
        }

        var sometext by xdStringProp()
        var value by xdLongProp()
    }

    private val init = 3
    private val dif = 3
    private val cycles = 10000

    private var size: Int = 0
    private var stopped = false

    override fun registerEntityTypes() {
        XdModel.registerNode(Something)
    }

    @Test
    fun testIncr() {
        val newSomething = transactional {
            Something.new()
        }

        val entityId = newSomething.xdId

        val latch = CountDownLatch(1)
        val threads = mutableListOf<Thread>()

        for (i in 0..12) {
            threads.add(thread {
                transactional {
                    val something = Something.findById(entityId)
                    latch.await()
                    something.value++
                }
            })
        }

        latch.countDown()

        for (t in threads) {
            t.join()
        }

        transactional {
            val something = Something.findById(entityId)
            println("Result of increment by 12 threads ${something.value}")
        }
    }

    @Test
    fun testInvariant() {
        val newSomething = transactional {
            Something.new()
        }

        val entityId = newSomething.xdId

        val firstGroup = CountDownLatch(1)
        val secondGroup = CountDownLatch(1)

        val threads = mutableListOf<Thread>()
        for (i in 0..6) {
            threads.add(thread {
                transactional {
                    val something = Something.findById(entityId)
                    firstGroup.await()
                    something.value = 500
                }
            })
        }

        for (i in 0..6) {
            threads.add(thread {
                transactional {
                    val something = Something.findById(entityId)
                    secondGroup.await()
                    if (something.value < 500)
                        something.value--
                }
            })
        }

        Thread.sleep(1_000)

        firstGroup.countDown()

        Thread.sleep(1_000)

        transactional {
            val something = Something.findById(entityId)
            println("First value seen by user ${something.value}")
        }

        secondGroup.countDown()

        for (t in threads) {
            t.join()
        }

        transactional {
            val something = Something.findById(entityId)
            println("Second value seen by user ${something.value}")
        }
    }

    @Test
    fun test1() {
        transactional {
            for (i in 0 until init) {
                Something.new("something $i")
            }
        }

        val t1 = checkThread()
        val t2 = removeThread()
        val t3 = populateThread()

        var e1: Throwable? = null
        var e2: Throwable? = null
        var e3: Throwable? = null
        t1.setUncaughtExceptionHandler { _, e -> e1 = e }
        t2.setUncaughtExceptionHandler { _, e -> e2 = e }
        t3.setUncaughtExceptionHandler { _, e -> e3 = e }

        t2.start()
        t3.start()
        t1.start()

        t1.join()
        t2.join()
        t3.join()

        if (e1 != null) {
            println("Exception in check thread")
            e1?.printStackTrace()
        }
        if (e2 != null) {
            println("Exception in delete thread")
            e2?.printStackTrace()
        }
        if (e3 != null) {
            println("Exception in add thread")
            e3?.printStackTrace()
        }
        assertThat(size == init || size == dif + init).isTrue()
    }

    private fun checkThread(): Thread {
        return thread(start = false, name = "CHECK_TREAD") {
            var i = 0
            while (i < cycles) {
                transactional {
                    size = getSize()
                    if (!(size == init || size == dif + init)) {
                        println("I saw $size somethings on $i iteration.")
                    }
                }
                Thread.yield()
                i++
            }
            stopped = true
        }
    }

    private fun removeThread(): Thread {
        return thread(start = false, name = "REMOVE_TREAD") {
            while (!stopped) {
                transactional { txn ->
                    val size = getSize()
                    Something.all()
                        .asSequence()
                        .take(max(size - init, 0))
                        .toList()
                        .forEach { it.delete() }
                    txn.flush()
                }
                Thread.yield()
            }
        }
    }

    private fun populateThread(): Thread {
        return thread(start = false, name = "ADD_TREAD") {
            while (!stopped) {
                transactional { txn ->
                    val size = getSize()
                    (0 until dif + init - size)
                        .forEach { Something.new("something $it") }
                    txn.flush()
                }
                Thread.yield()
            }
        }
    }

    private fun getSize(): Int {
        return Something.query(Something::sometext startsWith "something").size()
    }
}
