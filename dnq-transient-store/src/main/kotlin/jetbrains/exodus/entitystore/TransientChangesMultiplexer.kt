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
package jetbrains.exodus.entitystore

import jetbrains.exodus.core.dataStructures.hash.HashMap
import jetbrains.exodus.core.dataStructures.hash.HashSet
import jetbrains.exodus.core.execution.JobProcessor
import jetbrains.exodus.database.*
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.entitystore.listeners.AsyncListenersReplication
import mu.KLogging
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

open class TransientChangesMultiplexer @JvmOverloads constructor(val asyncJobProcessor: JobProcessor? = null) : TransientStoreSessionListener, ITransientChangesMultiplexer {

    private val instanceToListeners = HashMap<FullEntityId, Queue<IEntityListener<*>>>()
    internal val typeToListeners = HashMap<String, Queue<IEntityListener<*>>>()
    private val notAsyncStores = HashSet<TransientEntityStore>()
    private val rwl = ReentrantReadWriteLock()
    private var isOpen = true
    var asyncListenersReplication: AsyncListenersReplication? = null

    companion object : KLogging()

    override fun flushed(session: TransientStoreSession, changedEntities: Set<TransientEntityChange>) {
        // do nothing. actual job is in flushed(changesTracker)
    }

    /**
     * Called directly by transient session
     *
     * @param oldChangesTracker changes tracker to dispose after async job
     */
    override fun flushed(session: TransientStoreSession, oldChangesTracker: TransientChangesTracker, changesDescription: Set<TransientEntityChange>) {
        this.fire(session.store, Where.SYNC_AFTER_FLUSH, changesDescription)
        this.asyncFire(session, oldChangesTracker, changesDescription)
    }

    override fun beforeFlushBeforeConstraints(session: TransientStoreSession, changedEntities: Set<TransientEntityChange>) {
        this.fire(session.store, Where.SYNC_BEFORE_FLUSH_BEFORE_CONSTRAINTS, changedEntities)
    }

    override fun afterConstraintsFail(session: TransientStoreSession, exceptions: Set<DataIntegrityViolationException>) {}

    fun disableAsyncFor(store: TransientEntityStore) {
        rwl.write { notAsyncStores.add(store) }
    }

    private fun asyncFire(session: TransientStoreSession, changesTracker: TransientChangesTracker, changes: Set<TransientEntityChange>) {
        val store = session.store
        val asyncJobProcessor = asyncJobProcessor
        if (asyncJobProcessor != null && rwl.read { store !in notAsyncStores }) {
            asyncJobProcessor.queue(TransientChangesMultiplexerJob(store, this, changes, changesTracker))
        } else {
            changesTracker.dispose()
        }
    }

    internal fun fire(store: TransientEntityStore, where: Where, changes: Set<TransientEntityChange>) {
        changes.forEach {
            this.handlePerEntityChanges(where, it)
            this.handlePerEntityTypeChanges(store, where, it)
        }
    }

    override fun addListener(e: Entity, listener: IEntityListener<*>) {
        if ((e as TransientEntity).isNew) {
            throw IllegalStateException("Entity is not saved into database - you can't listen to it.")
        }
        val id = FullEntityId(e.store, e.getId())
        rwl.write {
            if (isOpen) {
                instanceToListeners
                        .getOrPut(id) { ConcurrentLinkedQueue() }
                        .add(listener)
            }
        }
    }

    override fun removeListener(e: Entity, listener: IEntityListener<*>) {
        val id = FullEntityId(e.store, e.id)
        rwl.write {
            val listeners = this.instanceToListeners[id]
            if (listeners != null) {
                listeners.remove(listener)
                if (listeners.isEmpty()) {
                    this.instanceToListeners.remove(id)
                }
            }
        }
    }

    override fun addListener(entityType: String, listener: IEntityListener<*>) {
        //  ensure that this code will be executed outside of transaction
        rwl.write {
            if (isOpen) {
                typeToListeners
                        .getOrPut(entityType) { ConcurrentLinkedQueue() }
                        .add(listener)
            }
        }
    }

    override fun removeListener(entityType: String, listener: IEntityListener<*>) {
        rwl.write {
            if (isOpen) {
                val listeners = this.typeToListeners[entityType]
                if (listeners != null) {
                    listeners.remove(listener)
                    if (listeners.isEmpty()) {
                        this.typeToListeners.remove(entityType)
                    }
                }
            }
        }
    }

    fun close() {
        logger.debug { "Cleaning EventsMultiplexer listeners" }

        val notClosedListeners = rwl.write {
            isOpen = false

            // clear listeners
            this.typeToListeners.clear()

            // copy set
            val notClosedListeners = HashMap<FullEntityId, Queue<IEntityListener<*>>>(this.instanceToListeners)
            instanceToListeners.clear()
            notClosedListeners
        }

        for ((id, listener) in notClosedListeners) {
            logger.error { listenerToString(id, listener) }
        }
    }

    override fun onClose(transientEntityStore: TransientEntityStore) {
        rwl.write { notAsyncStores.remove(transientEntityStore) }
    }

    private fun listenerToString(id: FullEntityId, listeners: Queue<IEntityListener<*>>): String {
        return buildString(40) {
            append("Unregistered entity to listener class: ")
            id.toString(this)
            append(" -> ")
            listeners.joinTo(this, " ") { it.javaClass.name }
        }
    }

    private fun handlePerEntityChanges(where: Where, c: TransientEntityChange) {
        val e = c.transientEntity
        val id = FullEntityId(e.store, e.id)
        val listeners = if (where == Where.ASYNC_AFTER_FLUSH && c.changeType == EntityChangeType.REMOVE) {
            // unsubscribe all entity listeners, but fire them anyway
            rwl.write {
                this.instanceToListeners.remove(id)
            }
        } else {
            rwl.read {
                this.instanceToListeners[id]
            }
        }
        if (listeners != null) {
            this.handleChange(where, c, listeners)
        }
    }

    private fun handlePerEntityTypeChanges(store: TransientEntityStore, where: Where, c: TransientEntityChange) {
        store.modelMetaData
                ?.getEntityMetaData(c.transientEntity.type)
                ?.thisAndSuperTypes
                ?.mapNotNull { rwl.read { this.typeToListeners[it] } }
                ?.forEach { this.handleChange(where, c, it) }
    }

    private fun handleChange(
            where: Where,
            c: TransientEntityChange,
            listeners: Queue<IEntityListener<*>>
    ) = when (where) {
        Where.SYNC_BEFORE_FLUSH_BEFORE_CONSTRAINTS -> when (c.changeType) {
            EntityChangeType.ADD -> listeners.visit(true) { it.addedSyncBeforeConstraints(c.transientEntity) }
            EntityChangeType.UPDATE -> listeners.visit(true) { it.updatedSyncBeforeConstraints(c.snapshotEntity, c.transientEntity) }
            EntityChangeType.REMOVE -> listeners.visit(true) { it.removedSyncBeforeConstraints(c.snapshotEntity) }
        }
        Where.SYNC_AFTER_FLUSH -> when (c.changeType) {
            EntityChangeType.ADD -> listeners.visit { it.addedSync(c.transientEntity) }
            EntityChangeType.UPDATE -> listeners.visit { it.updatedSync(c.snapshotEntity, c.transientEntity) }
            EntityChangeType.REMOVE -> listeners.visit { it.removedSync(c.snapshotEntity) }
        }
        Where.ASYNC_AFTER_FLUSH -> when (c.changeType) {
            EntityChangeType.ADD -> listeners.visitAsync(c) { it.addedAsync(c.transientEntity) }
            EntityChangeType.UPDATE -> listeners.visitAsync(c) { it.updatedAsync(c.snapshotEntity, c.transientEntity) }
            EntityChangeType.REMOVE -> listeners.visitAsync(c) { it.removedAsync(c.snapshotEntity) }
        }
    }

    private fun Queue<IEntityListener<*>>.visit(rethrow: Boolean = false, action: (IEntityListener<Entity>) -> Unit) {
        for (l in this) {
            try {
                @Suppress("UNCHECKED_CAST")
                action(l as IEntityListener<Entity>)
            } catch (e: Exception) {
                // rethrow exception only for beforeFlush listeners
                if (rethrow) {
                    if (e is RuntimeException) {
                        throw e
                    } else {
                        throw RuntimeException(e)
                    }
                } else {
                    logger.error(e) { "Exception while notifying entity listener." }
                }
            }
        }
    }

    private fun Queue<IEntityListener<*>>.visitAsync(c: TransientEntityChange, action: (IEntityListener<Entity>) -> Unit) {
        for (l in this) {
            try {
                @Suppress("UNCHECKED_CAST")
                action(l as IEntityListener<Entity>)

                // TODO what if code above will fail with exception?
                asyncListenersReplication?.replicate(c, l)
            } catch (e: Exception) {
                // rethrow exception only for beforeFlush listeners
                logger.error(e) { "Exception while notifying entity listener." }
            }
        }
    }
}
