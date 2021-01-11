/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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

import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import kotlinx.dnq.listener.removeListener
import kotlinx.dnq.query.size
import kotlinx.dnq.util.getAddedLinks
import kotlinx.dnq.util.hasChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EventsTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(Foo, Goo)
    }

    @Test
    fun accessAddedOnUpdatedAfterGetLinks() {
        val (f1, f2, g) = transactional {
            Triple(Foo.new(), Foo.new(), Goo.new())
        }
        val contentChanged = AtomicBoolean(false)
        val contentsAdded = AtomicBoolean(false)
        Goo.onUpdate { old, _ ->
            contentChanged.set(old.hasChanges(Goo::content))
            contentsAdded.set(old.getAddedLinks(Goo::content).size() == 2)
        }
        transactional {
            g.content.add(f1)
            g.content.add(f2)
        }
        assertTrue(contentChanged.get())
        assertTrue(contentsAdded.get())
    }

    @Test
    fun removeTypeListener() {
        val goo = transactional { Goo.new() }
        val addedCount = AtomicInteger(0)
        val listener = createIncrementListener(addedCount)
        store.changesMultiplexer?.addListener(Goo, listener)
        try {
            transactional {
                goo.content.add(Foo.new())
            }
        } finally {
            store.changesMultiplexer?.removeListener(Goo, listener)
        }
        assertEquals(1, addedCount.get())
        transactional {
            goo.content.add(Foo.new())
        }
        assertEquals(1, addedCount.get())
    }

    @Test
    fun removeInstanceListener() {
        val goo = transactional { Goo.new() }
        val addedCount = AtomicInteger(0)
        val listener = createIncrementListener(addedCount)
        try {
            transactional {
                store.changesMultiplexer?.addListener(goo, listener)
                goo.content.add(Foo.new())
            }
        } finally {
            store.changesMultiplexer?.removeListener(goo, listener)
        }
        assertEquals(1, addedCount.get())
        transactional {
            goo.content.add(Foo.new())
        }
        assertEquals(1, addedCount.get())
    }

    private fun createIncrementListener(addedCount: AtomicInteger): XdEntityListener<Goo> {
        return object : XdEntityListener<Goo> {
            override fun updatedSync(old: Goo, current: Goo) {
                addedCount.addAndGet(old.getAddedLinks(Goo::content).size())
            }
        }
    }

}
