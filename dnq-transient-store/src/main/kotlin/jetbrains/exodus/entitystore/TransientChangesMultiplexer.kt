/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
package jetbrains.exodus.entitystore

import jetbrains.exodus.core.dataStructures.hash.HashMap
import jetbrains.exodus.database.*
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import mu.KLogging
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

open class TransientChangesMultiplexer :
    TransientStoreSessionListener, ITransientChangesMultiplexer {

    private val instanceToListeners = HashMap<FullEntityId, Queue<IEntityListener<*>>>()
    val typeToListeners = HashMap<String, Queue<IEntityListener<*>>>()
    private val rwl = ReentrantReadWriteLock()
    private var isOpen = true

    companion object : KLogging()

    override fun flushed(session: TransientStoreSession, changedEntities: Set<TransientEntityChange>) {
        // Do nothing. Actual job is in flushed(changesTracker)
    }

    /**
     * Called directly by transient session
     *
     * @param oldChangesTracker changes tracker to dispose after the async job
     */
    override fun flushed(
        session: TransientStoreSession,
        oldChangesTracker: TransientChangesTracker,
        changesDescription: Set<TransientEntityChange>
    ) {
        this.fire(session, Where.SYNC_AFTER_FLUSH, changesDescription)
    }

    override fun beforeFlushBeforeConstraints(
        session: TransientStoreSession,
        changedEntities: Set<TransientEntityChange>
    ) {
        this.fire(session, Where.SYNC_BEFORE_FLUSH_BEFORE_CONSTRAINTS, changedEntities)
    }

    override fun afterConstraintsFail(
        session: TransientStoreSession,
        exceptions: Set<DataIntegrityViolationException>
    ) {
    }


    private fun fire(
        session: TransientStoreSession,
        where: Where,
        changes: Set<TransientEntityChange>
    ) {
        changes.forEach {
            this.handlePerEntityChanges(session, where, it)
            this.handlePerEntityTypeChanges(session, where, it)
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

    @Suppress("unused")
    fun close() {
        logger.debug { "Cleaning EventsMultiplexer listeners" }

        val notClosedListeners = rwl.write {
            isOpen = false

            // clear listeners
            this.typeToListeners.clear()

            // copy set
            val notClosedListeners = HashMap(this.instanceToListeners)
            instanceToListeners.clear()
            notClosedListeners
        }

        for ((id, listener) in notClosedListeners) {
            logger.error { listenerToString(id, listener) }
        }
    }

    override fun onClose(transientEntityStore: TransientEntityStore) {
    }

    private fun listenerToString(id: FullEntityId, listeners: Queue<IEntityListener<*>>): String {
        return buildString(40) {
            append("Unregistered entity to listener class: ")
            id.toString(this)
            append(" -> ")
            listeners.joinTo(this, " ") { it.javaClass.name }
        }
    }

    private fun handlePerEntityChanges(
        session: TransientStoreSession,
        where: Where,
        c: TransientEntityChange
    ) {
        val e = c.transientEntity
        val id = FullEntityId(e.store, e.id)
        val listeners = if (c.changeType == EntityChangeType.REMOVE) {
            // unsubscribe all entity listeners but fire them anyway
            rwl.write {
                this.instanceToListeners.remove(id)
            }
        } else {
            rwl.read {
                this.instanceToListeners[id]
            }
        }
        if (listeners != null) {
            this.handleChange(where, session, c, listeners)
        }
    }

    private fun handlePerEntityTypeChanges(
        session: TransientStoreSession,
        where: Where, c: TransientEntityChange
    ) {
        session.store.modelMetaData
            ?.getEntityMetaData(c.transientEntity.type)
            ?.thisAndSuperTypes
            ?.mapNotNull { rwl.read { this.typeToListeners[it] } }
            ?.forEach { this.handleChange(where, session, c, it) }
    }

    private fun handleChange(
        where: Where,
        session: TransientStoreSession,
        c: TransientEntityChange,
        listeners: Queue<IEntityListener<*>>
    ) = when (where) {
        Where.SYNC_BEFORE_FLUSH_BEFORE_CONSTRAINTS -> when (c.changeType) {
            EntityChangeType.ADD -> listeners.visit(true) { it.addedSyncBeforeConstraints(c.transientEntity) }
            EntityChangeType.UPDATE -> listeners.visit(true) { it.updatedSyncBeforeConstraints(c.snapshotEntity, c.transientEntity) }
            EntityChangeType.REMOVE -> listeners.visit(true) { listener ->
                listener.removedSyncBeforeConstraints(c.snapshotEntity, session.createRemovedEntityData(listener, c.snapshotEntity))
            }
        }

        Where.SYNC_AFTER_FLUSH -> when (c.changeType) {
            EntityChangeType.ADD -> listeners.visit { it.addedSync(c.transientEntity) }
            EntityChangeType.UPDATE -> listeners.visit { it.updatedSync(c.snapshotEntity, c.transientEntity) }
            EntityChangeType.REMOVE -> listeners.visit { listener ->
                listener.removedSync(session.getRemovedEntityData(listener, c.snapshotEntity.id))
            }
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

}
