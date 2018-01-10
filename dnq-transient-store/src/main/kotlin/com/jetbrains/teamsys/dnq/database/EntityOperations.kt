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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.core.dataStructures.hash.HashSet
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import mu.NamedKLogging

// TODO: move this class to the associations semantics package
object EntityOperations {
    private val logger = NamedKLogging(EntityOperations::class.java.name).logger

    @JvmStatic
    fun remove(e: Entity?) {
        /* two-phase remove:
           1. call destructors
           2. remove links and entities
        */

        remove(e, true, HashSet())
        remove(e, false, HashSet())
    }

    @JvmStatic
    internal fun remove(e: Entity?, callDestructorPhase: Boolean, processed: MutableSet<Entity>) {
        if (e == null || (e as TransientEntity).isRemoved) return
        val txnEntity = e.reattachTransient()

        if (txnEntity in processed) return

        val store = txnEntity.store

        val modelMetaData = store.modelMetaData
        if (modelMetaData != null) {
            // cascade delete
            val entityMetaData = modelMetaData.getEntityMetaData(txnEntity.type)
            if (entityMetaData != null) {
                if (callDestructorPhase) {
                    txnEntity.persistentClassInstance?.destructor(txnEntity)
                }
                processed.add(txnEntity)
                // remove associations and cascade delete
                val storeSession = store.threadSessionOrThrow
                ConstraintsUtil.processOnDeleteConstraints(storeSession, txnEntity, entityMetaData, modelMetaData, callDestructorPhase, processed)
            }
        }

        if (!callDestructorPhase) {
            // delete itself; the check is performed, because onDelete constraints could already delete entity 'e'
            if (!txnEntity.isRemoved) {
                txnEntity.delete()
            }
        }
    }

    /**
     * Checks if entity e was removed
     *
     * @param e entity to check
     * @return true if e was removed, false if it wasn't removed at all
     */
    @JvmStatic
    fun isRemoved(e: Entity?): Boolean {
        return if (e == null) {
            true
        } else {
            TransientStoreUtil.isRemoved(e)
        }
    }

    @JvmStatic
    fun isNew(e: Entity?): Boolean {
        return e?.reattachTransient()?.isNew ?: false
    }

    @JvmStatic
    fun equals(e1: Entity?, e2: Any?): Boolean {
        if (e1 === e2) return true
        if (e1 == null) return false
        if (e1 !is TransientEntity) return false
        return e1 == e2
    }

    /**
     * Slow method! Use with care.
     *
     * @param entities iterable to index
     * @param i        queried element index
     * @return element at position i in entities iterable
     */
    @JvmStatic
    @Deprecated("Slow method. For TestCases only")
    fun getElement(entities: Iterable<Entity>, i: Int): Entity {
        logger.warn { "Slow method EntityOperations.getElement() was called!" }
        return entities.elementAt(i)
    }

    @JvmStatic
    fun hasChanges(e: TransientEntity): Boolean {
        return e.reattach().hasChanges()
    }

    @JvmStatic
    fun hasChanges(e: TransientEntity, property: String): Boolean {
        return e.reattach().hasChanges(property)
    }

    @JvmStatic
    fun hasChanges(e: TransientEntity, properties: Array<String>): Boolean {
        val entity = e.reattach()
        return properties.any { entity.hasChanges(it) }
    }

    @JvmStatic
    fun hasChangesExcepting(e: TransientEntity, properties: Array<String>): Boolean {
        val entity = e.reattach()
        return entity.hasChangesExcepting(properties)
    }
}
