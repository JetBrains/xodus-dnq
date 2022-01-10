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
package kotlinx.dnq.concurrent.transaction


import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.query.asSequence
import kotlinx.dnq.query.first
import kotlinx.dnq.query.isNotEmpty
import org.junit.Test
import kotlin.concurrent.thread

class TransactionsIsolation2Test : DBTest() {
    class Something(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Something>() {
            fun new(text: String) = Something.new {
                this.sometext = text
            }
        }

        var sometext by xdStringProp()
    }


    private val init = 100
    private val cycles = 1000

    private var stopped = false

    val sizes: IntArray
        get() {
            val res = IntArray(3)
            Something.all().asSequence().forEach { it ->
                when {
                    it.sometext == "asomething" -> res[0] += 1
                    it.sometext == "bsomething" -> res[1] += 1
                    else -> res[2] += 1
                }
            }
            return res
        }

    override fun registerEntityTypes() {
        XdModel.registerNode(Something)
    }

    @Test
    fun test1() {
        transactional { txn ->
            while (Something.all().isNotEmpty) {
                Something.all().first().delete()
                txn.flush()
            }
        }
        transactional {
            for (i in 0 until init) {
                Something.new("asomething")
            }
        }

        var e1: Throwable? = null
        val t1 = checkThread().also {
            it.setUncaughtExceptionHandler { _, e -> e1 = e }
            it.start()
        }

        var e2: Throwable? = null
        val t2 = updateThread().also {
            it.setUncaughtExceptionHandler { _, e -> e2 = e }
            it.start()
        }

        t1.join()
        t2.join()

        if (e1 != null) {
            println("Exception in check thread")
            e1?.printStackTrace()
        }
        if (e2 != null) {
            println("Exception in update thread")
            e2?.printStackTrace()
        }
    }

    private fun checkThread(): Thread {
        return thread(start = false, name = "CHECK_TREAD") {
            var i = 0
            while (i < cycles) {
                transactional {
                    if (i % 1000 == 0) {
                        println("Check cycle $i")
                    }
                    val s = sizes
                    if (s[0] == init && s[1] == 0 && s[2] == 0 || s[0] == 0 && s[1] == init && s[2] == 0) {
                        // ok
                    } else {
                        println("I see a = ${s[0]} and b = ${s[1]} and unknown = ${s[2]} on $i iteration.")
                    }
                }
                Thread.yield()
                i++
            }
            stopped = true
        }
    }

    private fun updateThread(): Thread {
        return thread(start = false, name = "UPDATE_TREAD") {
            var a = true
            while (!stopped) {
                transactional { txn ->
                    Something.all().asSequence().forEach {
                        it.sometext = if (a) "bsomething" else "asomething"
                    }
                    println("Update before flush.")
                    txn.flush()
                    println("Update after flush.")
                }
                a = !a
                Thread.yield()
            }
        }
    }
}
