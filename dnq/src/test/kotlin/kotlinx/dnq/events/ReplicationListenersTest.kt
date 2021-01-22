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
import kotlinx.dnq.store.container.createTransientEntityStore
import org.junit.After
import org.junit.Before

class ReplicationListenersTest : AsyncListenersTest() {

    private lateinit var secondaryStore: TransientEntityStoreImpl

    @Before
    fun prepare() {
        secondaryStore = createTransientEntityStore(
                dbFolder = databaseHome,
                entityStoreName = "testDB",
                primary = false) {
            logDataReaderWriterProvider = "jetbrains.exodus.io.WatchingFileDataReaderWriterProvider"
            envCloseForcedly = true
        }
        secondaryStore.changesMultiplexer = store.changesMultiplexer
        transport.addReceiver(secondaryStore, replication)
    }

    @After
    fun done() {
        with(secondaryStore) {
            close()
            persistentStore.close()
            persistentStore.environment.close()
        }
    }
}