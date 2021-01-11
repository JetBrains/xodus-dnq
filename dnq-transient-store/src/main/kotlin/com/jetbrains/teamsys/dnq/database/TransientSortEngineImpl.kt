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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.QueryEngine
import jetbrains.exodus.query.SortEngine

class TransientSortEngineImpl(private val store: TransientEntityStore, queryEngine: QueryEngine) : SortEngine(queryEngine) {

    override fun attach(entity: Entity): Entity {
        // TODO: seems that it's needed only for compatibility
        return store.threadSession?.newEntity(entity) ?: entity
    }
}
