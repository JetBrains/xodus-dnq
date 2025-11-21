/**
 * Copyright 2006 - 2025 JetBrains s.r.o.
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
package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.first
import kotlinx.dnq.query.toSet
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplayTest : DBTest() {

    class SimpleCounter(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<SimpleCounter>()

        var lastWriter by xdRequiredIntProp()
    }

    class SetHolder(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<SetHolder>()

        var aSet by xdSetProp<SetHolder, Int>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(SimpleCounter, SetHolder)
    }

    @Test
    fun testReplay() {
        transactional {
            SimpleCounter.new { lastWriter = 0 }
        }

        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)

        val t1 = thread {
            transactional {
                SimpleCounter.all().first().lastWriter = 1
                SetHolder.new { aSet = setOf(1) }
                latch1.countDown()
                latch2.await()
            }
        }
        val t2 = thread {
            transactional {
                latch1.await()
                latch2.countDown()
                SimpleCounter.all().first().lastWriter = 2
                SetHolder.new { aSet = setOf(2) }
            }
        }

        listOf(t1, t2).forEach { it.join() }

        transactional {
            assertEquals(2, SimpleCounter.all().first().lastWriter)
            assertEquals(
                setOf(setOf(1), setOf(2)),
                SetHolder.all().toSet().map { it.aSet.toSet() }.toSet()
            )
        }
    }
}