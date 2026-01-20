/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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
package jetbrains.exodus.entitystore.youtrackdb

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import jetbrains.exodus.Questionable

@Questionable("Not used probably")
class YTDBReadonlyVertexEntity(vertex: YTDBVertex, store: YTDBEntityStore) : YTDBVertexEntity(vertex, store) {
    override fun requireActiveWritableTransaction(): YTDBStoreTransaction {
        throw IllegalArgumentException("Can't update readonly entity (id=${id})")
    }

    override fun resetToNew() {
        throw UnsupportedOperationException("Not supported in readonly entity")
    }
}

