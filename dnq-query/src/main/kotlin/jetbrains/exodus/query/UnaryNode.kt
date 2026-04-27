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

import jetbrains.exodus.core.dataStructures.NanoSet
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.query.metadata.ModelMetaData

class UnaryNode(
    val child: NodeBase,
    val shortName: String,
    val op: (GremlinBlock) -> GremlinBlock
) : NodeBase() {
    private var children: Set<NodeBase>? = null

    init {
        this.child.parent = this
    }

    override fun getChildren(): Collection<NodeBase> {
        if (children == null) {
            children = NanoSet(child)
        }
        return children!!
    }

    override fun getQuery(): GremlinQuery {
        val condition = child.query as? GremlinQuery.Condition
            ?: throw IllegalArgumentException("Only Condition instances can be used in the chain")

        return condition.combineUnary(op)
    }

    override fun instantiate(
        entityType: String,
        queryEngine: QueryEngine,
        metaData: ModelMetaData?,
        polymorphic: Boolean
    ): Iterable<Entity> = YTDBEntityIterable.query(
        queryEngine.oStore.requireActiveTransaction(),
        query.then(GremlinBlock.HasLabel(entityType)),
        polymorphic
    )

    override fun getClone(): NodeBase = UnaryNode(child.clone, shortName, op)

    override fun getSimpleName(): String = shortName

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }

        if (other == null) {
            return false
        }

        return other is UnaryNode && other.shortName == this.shortName && super.equals(other)
    }
}