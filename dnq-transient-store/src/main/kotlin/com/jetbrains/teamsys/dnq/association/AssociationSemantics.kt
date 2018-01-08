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
package com.jetbrains.teamsys.dnq.association

import com.jetbrains.teamsys.dnq.database.*
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntityStore

object AssociationSemantics {

    /**
     * To one association end getter.
     * Supports nullable objects - input entity may be null
     */
    @JvmStatic
    fun getToOne(e: Entity?, linkName: String): Entity? {
        // nullable objects support
        return e?.reattachTransient()?.getLink(linkName)
    }

    @JvmStatic
    fun getToMany(e: Entity?, linkName: String): Iterable<Entity> {
        return e?.reattachTransient()?.getLinks(linkName)
                ?: UniversalEmptyEntityIterable.INSTANCE
    }

    @JvmStatic
    fun getToMany(e: Entity?, linkNames: Set<String>): Iterable<Entity> {
        return e?.reattachTransient()?.getLinks(linkNames)
                ?: UniversalEmptyEntityIterable.INSTANCE
    }

    /**
     * Returns copy of [.getToMany] iterable
     */
    @JvmStatic
    fun getToManyList(e: Entity, linkName: String): List<Entity> {
        return getToMany(e, linkName).toList()
    }

    /**
     * Returns persistent iterable if possible
     */
    @JvmStatic
    fun getToManyPersistentIterable(e: Entity, linkName: String): Iterable<Entity> {
        val txnEntity = e.reattachTransient()

        // can't return persistent iterable for new transient entity
        return if (txnEntity.isNew) {
            //throw new IllegalStateException("1111");
            txnEntity.getLinks(linkName)
        } else {
            txnEntity.persistentEntity.getLinks(linkName)
        }
    }

    /**
     * Returns links size
     */
    @JvmStatic
    fun getToManySize(e: Entity, linkName: String): Long {
        return if (e is TransientEntity) {
            val txnEntity = e.reattach()
            txnEntity.getLinksSize(linkName)
        } else {
            TransientStoreUtil.getSize(e.getLinks(linkName)).toLong()
        }
    }

    /**
     * Returns added links
     */
    @JvmStatic
    fun getAddedLinks(e: TransientEntity, name: String): EntityIterable {
        return e.reattach().getAddedLinks(name)
    }

    /**
     * Returns removed links
     */
    @JvmStatic
    fun getRemovedLinks(e: TransientEntity, name: String): EntityIterable {
        return e.reattach().getRemovedLinks(name)
    }

    @JvmStatic
    fun getAddedLinks(e: TransientEntity, linkNames: Set<String>): EntityIterable {
        return e.reattach().getAddedLinks(linkNames)
    }

    @JvmStatic
    fun getRemovedLinks(e: TransientEntity, linkNames: Set<String>): EntityIterable {
        return e.reattach().getRemovedLinks(linkNames)
    }

    /**
     * Returns previous link value
     */
    @JvmStatic
    fun getOldValue(e: TransientEntity, name: String): Entity? {
        return if (EntityOperations.isRemoved(e)) {
            val transientStore = e.store
            (transientStore.persistentStore as PersistentEntityStore)
                    .getEntity(e.id)
                    .getLink(name)
                    ?.let { result ->
                        transientStore.threadSessionOrThrow.newEntity(result)
                    }
        } else {
            getRemovedLinks(e, name).firstOrNull()
        }
    }

}
