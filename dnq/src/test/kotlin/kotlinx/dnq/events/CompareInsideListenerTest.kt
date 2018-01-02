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
package kotlinx.dnq.events

import jetbrains.exodus.core.execution.JobProcessor
import jetbrains.exodus.entitystore.EventsMultiplexer
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CompareInsideListenerTest : DBTest() {

    @Before
    fun updateMultiplexer() {
        store.eventsMultiplexer = EventsMultiplexer(createAsyncProcessor().apply(JobProcessor::start))
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Bar)
    }

    @Test
    fun updateSync() {
        val called = AtomicBoolean(false)
        val b = transactional {
            Bar.new {
                bar = "bar"
            }
        }
        transactional {
            b.onUpdate { old, current ->
                assertTrue(b == b)
                assertTrue(old == current)
                assertTrue(current == b)
                assertTrue(b == current)
                assertTrue(current == current)
                assertTrue(old == b)
                assertTrue(b == old)
                assertTrue(old == old)
                called.set(true)
            }
        }
        transactional {
            b.bar = "bar1"
        }
        assertTrue(called.get())
    }
}
