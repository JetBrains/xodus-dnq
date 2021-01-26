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

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.io.DataReaderWriterProvider
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import kotlinx.dnq.store.container.createTransientEntityStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

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
        transport.addReceiver(secondaryStore, replication)
    }

    @Test
    fun `updated invoked`() {
        var invocations = 0
        Bar.addListener(store, object : XdEntityListener<Bar> {
            override fun updatedAsync(old: Bar, current: Bar) {
                /*if (current.hasChanges(Bar::bar)) {
                    ++invocations
                }
                if (current.getOldValue(Bar::bar).isNullOrEmpty()) {
                    ++invocations
                }*/
                ++invocations
            }
        })
        val bar = transactional { Bar.new() }
        transactional { bar.bar = "xxx" }

        `wait for pending invocations`()
        assertEquals(2, invocations)
    }

    @Test
    fun `added invoked`() {
        var invocations = 0
        Bar.addListener(store, object : XdEntityListener<Bar> {
            override fun addedAsync(added: Bar) {
                ++invocations
            }
        })
        transactional { Bar.new() }

        `wait for pending invocations`()
        assertEquals(2, invocations)
    }

    @Test
    fun `removed invoked`() {
        var invocations = 0
        Bar.addListener(store, object : XdEntityListener<Bar> {
            override fun removedAsync(removed: Bar) {
                ++invocations
            }
        })
        val bar = transactional { Bar.new() }
        transactional { bar.delete() }

        `wait for pending invocations`()
        assertEquals(2, invocations)
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
        transport.waitForPendingInvocations(secondaryStore)
    }
}