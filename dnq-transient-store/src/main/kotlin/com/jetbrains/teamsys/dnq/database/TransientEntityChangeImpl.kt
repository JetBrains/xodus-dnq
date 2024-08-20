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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.*
import jetbrains.exodus.entitystore.orientdb.*

class TransientEntityChangeImpl(
    private val changesTracker: TransientChangesTracker,
    override val transientEntity: TransientEntity,
    override val changedProperties: Set<String>?,
    override val changedLinksDetailed: Map<String, LinkChange>?,
    override val changeType: EntityChangeType
) : TransientEntityChange {


    private var snapshotForRemoveOperation: TransientEntity? = null

    init {
        if (changeType == EntityChangeType.REMOVE) {
            try {
                val precalculatedSnapshot = (transientEntity.entity as OVertexEntity).asReadonly()
                snapshotForRemoveOperation =
                    ReadonlyTransientEntityImpl(this, precalculatedSnapshot, transientEntity.store)
            } catch (_: Exception) {
            }
        }
    }

    override val snapshotEntity: TransientEntity
        get() {
            return when {
                snapshotForRemoveOperation != null -> snapshotForRemoveOperation!!

                changesTracker.isRemoved(transientEntity) -> RemovedTransientEntity(
                    transientEntity.id as OEntityId,
                    transientEntity.store,
                    transientEntity.type
                )

                else -> changesTracker.getSnapshotEntity(transientEntity)
            }
        }


    override fun toString() = "$changeType:$transientEntity"

}
