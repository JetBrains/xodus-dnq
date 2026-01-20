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
package jetbrains.exodus.query

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.query.metadata.ModelMetaData
import java.util.*
import javax.annotation.Nonnull

class LeafNode(private val query: GremlinQuery) : NodeBase() {
    constructor(block: GremlinBlock) : this(GremlinQuery.all.then(block))

    companion object {
        val none = LeafNode(GremlinBlock.None)
    }

    @Nonnull
    override fun getQuery(): GremlinQuery = query

    override fun instantiate(
        entityType: String,
        queryEngine: QueryEngine,
        metaData: ModelMetaData?
    ): Iterable<Entity> = YTDBEntityIterable.query(
        queryEngine.oStore.requireActiveTransaction(),
        query.then(GremlinBlock.HasLabel(entityType))
    )

    override fun getClone(): NodeBase = LeafNode(query)

    override fun getSimpleName(): String = query.shortName()

    override fun size(): Int = 1

    override fun toString(): String {
        // todo:
        return getSimpleName()
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null) return false

        return o is LeafNode && query == o.query
    }

    override fun hashCode(): Int {
        return Objects.hashCode(query)
    }
}