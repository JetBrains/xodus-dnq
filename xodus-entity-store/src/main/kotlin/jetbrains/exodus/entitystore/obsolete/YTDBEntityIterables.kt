package jetbrains.exodus.entitystore.obsolete

import com.jetbrains.youtrackdb.api.query.ResultSet
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityStore
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity

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
fun ResultSet.toEntityIterator(store: YTDBEntityStore): Iterator<Entity> {
    return this.vertexStream().map { YTDBVertexEntity(it, store) }.iterator()
}