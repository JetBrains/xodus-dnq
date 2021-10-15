/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package jetbrains.exodus.entitystore.listeners

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.core.dataStructures.Priority
import jetbrains.exodus.core.execution.Job
import jetbrains.exodus.entitystore.TransientChangesMultiplexer
import mu.KLogging

interface ListenerInvocationTransport {

    fun send(batch: ListenerInvocationsBatch)

    fun addReceiver(replication: AsyncListenersReplication)

    fun waitForPendingInvocations()
}

abstract class AbstractInvocationTransport(
    val store: TransientEntityStoreImpl,
    val changesMultiplexer: TransientChangesMultiplexer
) : ListenerInvocationTransport {

    companion object : KLogging()

    private var replication: AsyncListenersReplication? = null

    override fun send(batch: ListenerInvocationsBatch) {
        val processor = changesMultiplexer.asyncJobProcessor
        val location = store.location
        val replication = replication
        if (processor != null && replication != null) {
            object : Job(processor) {

                override fun execute() {
                    store.waitForPendingChanges(batch.endHighAddress)
                    try {
                        logger.info { "SECONDARY($location): receive batch" }
                        batch.invocations.forEach {
                            logger.info { "SECONDARY($location): calling ${it.entityId} ${it.changeType} ${it.listenerKey}" }
                        }
                        replication.receive(store, batch)
                    } catch (e: Exception) {
                        logger.error("Can't replicate listeners invocations", e)
                    }
                }

            }.queue(Priority.normal)
        }
    }

    override fun addReceiver(replication: AsyncListenersReplication) {
        this.replication = replication
    }

    override fun waitForPendingInvocations() {
        changesMultiplexer.asyncJobProcessor?.waitForJobs(100)
    }
}

open class InMemoryTransport(store: TransientEntityStoreImpl, changesMultiplexer: TransientChangesMultiplexer) :
    AbstractInvocationTransport(store, changesMultiplexer) {

    companion object : KLogging()

}