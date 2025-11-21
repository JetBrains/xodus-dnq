/**
 * Copyright 2006 - 2025 JetBrains s.r.o.
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

class TransientEntityChangeImpl(
    override val changesTracker: TransientChangesTracker,
    override val transientEntity: TransientEntity,
    override val changedProperties: Map<String, Comparable<*>?>?,
    override val changedLinksDetailed: Map<String, LinkChange>?,
    override val changeType: EntityChangeType
) : TransientEntityChange {


    override val snapshotEntity: TransientEntity
        get() = changesTracker.getSnapshotEntity(transientEntity)

    override fun toString() = "$changeType:$transientEntity"
}
