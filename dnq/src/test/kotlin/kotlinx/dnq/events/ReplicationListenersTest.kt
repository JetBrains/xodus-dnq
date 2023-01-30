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
package kotlinx.dnq.events

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.io.DataReaderWriterProvider
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import kotlinx.dnq.query.toList
import kotlinx.dnq.store.container.createTransientEntityStore
import kotlinx.dnq.util.getOldValue
import kotlinx.dnq.util.hasChanges
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReplicationListenersTest : AsyncListenersBaseTest() {

    private lateinit var secondaryStore: TransientEntityStoreImpl

    @Before
    fun prepare() {
        secondaryStore = createTransientEntityStore(
                dbFolder = databaseHome,
                entityStoreName = "testDB",
                primary = false) {
            logDataReaderWriterProvider = DataReaderWriterProvider.WATCHING_READER_WRITER_PROVIDER
            isGcEnabled = false
            envCloseForcedly = true
        }
        secondaryStore.changesMultiplexer = store.changesMultiplexer
    }

    @Test
    fun `updated invoked`() {
        var count = 0
        val bar = transactional { Bar.new() }
        Bar.addListener(store, object : XdEntityListener<Bar> {
            override fun updatedAsync(old: Bar, current: Bar) {
                if (old.hasChanges(Bar::bar)) {
                    ++count
                }
                if (old.getOldValue(Bar::bar).isNullOrEmpty()) {
                    ++count
                }
                if (current.hasChanges(Bar::bar)) {
                    ++count
                }
                if (current.getOldValue(Bar::bar).isNullOrEmpty()) {
                    ++count
                }
                ++count
            }
        })
        transactional { bar.bar = "xxx" }

        `wait for pending invocations`()
        assertEquals(10, count)
    }

    @Test
    fun `listener inheritance should be checked`() {
        val bar = transactional { Bar.new() }
        val listener = SuperBarListener()
        Bar.addListener(store, listener)
        transactional { bar.bar = "xxx" }

        `wait for pending invocations`()
        assertEquals(10, listener.count)
    }

    @Test
    fun `links should be visible`() {
        var count = 0
        val listener = object : XdEntityListener<Goo> {
            override fun addedAsync(added: Goo) {
                added.content.toList().forEach {
                    println(it.intField)
                    count++
                }
            }
        }

        Goo.addListener(store, listener)
        val pool = Executors.newCachedThreadPool()
        var task = 0
        repeat(10) {
            pool.submit {
                task++
                transactional {
                    var i = 0
                    repeat(10) {
                        Foo.new { intField = i++ }
                    }
                    it.flush()
                    Goo.new {
                        repeat(10) {
                            content.add(Foo.new { intField = i++ })
                        }
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.MINUTES)
        `wait for pending invocations`()
        assertEquals(200, count)
    }

    @Test
    fun `added invoked`() {
        var count = 0
        Bar.addListener(store, object : XdEntityListener<Bar> {
            override fun addedAsync(added: Bar) {
                ++count
                if (added.isNew) {
                    ++count
                }
            }
        })
        transactional { Bar.new() }

        `wait for pending invocations`()
        assertEquals(4, count)
    }

    @Test
    fun `removed invoked`() {
        var count = 0
        val bar = transactional { Bar.new().apply { bar = "xxx" } }
        Bar.addListener(store, object : XdEntityListener<Bar> {
            override fun removedAsync(removed: Bar) {
                ++count
                if (removed.isRemoved) {
                    ++count
                }
                if (removed.getOldValue(Bar::bar) == "xxx") {
                    ++count
                }
            }
        })
        transactional { bar.delete() }

        `wait for pending invocations`()
        assertEquals(6, count)
    }

    @After
    fun done() {
        with(secondaryStore) {
            close()
            persistentStore.close()
            persistentStore.environment.close()
        }
    }

    private fun `wait for pending invocations`() {
        asyncProcessor.waitForJobs(100)
        store.invocationTransport?.waitForPendingInvocations()
    }
}

open class BarListener(var count: Int) : XdEntityListener<Bar> {
    override fun updatedAsync(old: Bar, current: Bar) {
        if (old.hasChanges(Bar::bar)) {
            ++count
        }
        if (old.getOldValue(Bar::bar).isNullOrEmpty()) {
            ++count
        }
        if (current.hasChanges(Bar::bar)) {
            ++count
        }
        if (current.getOldValue(Bar::bar).isNullOrEmpty()) {
            ++count
        }
        ++count
    }
}

open class SuperBarListener : BarListener(0)
