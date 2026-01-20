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
import org.slf4j.LoggerFactory
import java.lang.Integer.max

@Suppress("LeakingThis")
open class BinaryNode(
    val left: NodeBase,
    val right: NodeBase,
    val commutative: Boolean = false,
    val shortName: String,
    private val combineQuery: (GremlinBlock, GremlinBlock) -> GremlinBlock,
    private val combineInMem: ((Iterable<Entity>, Iterable<Entity>) -> Iterable<Entity>)? = null
) : NodeBase() {

    private var children: List<NodeBase>? = null
    private var depth: Int = 0

    init {
        this.left.parent = this
        this.right.parent = this
        invalidateDepth(left, right)
        if (isWarnEnabled && depth >= MAXIMUM_LEGAL_DEPTH && depth % MAXIMUM_LEGAL_DEPTH == 0) {
            val millis = System.currentTimeMillis()
            if (millis - lastLoggedMillis > LARGE_DEPTH_LOGGING_FREQ) {
                lastLoggedMillis = millis
                logger.warn("Binary operator of too great depth", Throwable())
            }
        }
    }

    override fun getQuery(): GremlinQuery {
        val leftCondition = (left.query as? GremlinQuery.Condition)
            ?: throw IllegalArgumentException("Only Condition instances can be used in the chain. Found: ${left.query}")
        val rightCondition = (right.query as? GremlinQuery.Condition)
            ?: throw IllegalArgumentException("Only Condition instances can be used in the chain. Found: ${right.query}")

        return leftCondition.combineBinary(rightCondition, combineQuery)
    }

    override fun instantiate(
        entityType: String,
        queryEngine: QueryEngine,
        metaData: ModelMetaData?
    ): Iterable<Entity> =
        YTDBEntityIterable.query(
            queryEngine.oStore.requireActiveTransaction(),
            query.then(GremlinBlock.HasLabel(entityType))
        )

    override fun getClone(): NodeBase =
        BinaryNode(left.clone, right.clone, commutative, shortName, combineQuery, combineInMem)

    override fun getSimpleName(): String = shortName

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        if (other == null) {
            return false
        }
        return other is BinaryNode && other.shortName == this.shortName && super.equals(other)
    }

    override fun getChildren(): Collection<NodeBase> =
        children ?: ArrayList<NodeBase>(2).apply {
            add(left)
            add(right)
            children = this
    }

    private fun invalidateDepth(left: NodeBase, right: NodeBase) {
        val leftDepth = (left as? BinaryNode)?.depth ?: 1
        val rightDepth = (right as? BinaryNode)?.depth ?: 1
        depth = max(leftDepth, rightDepth) + 1
    }

    companion object {

        private val logger = LoggerFactory.getLogger(BinaryNode::class.java)
        private val isWarnEnabled = logger.isWarnEnabled
        private const val MAXIMUM_LEGAL_DEPTH = 200
        private const val LARGE_DEPTH_LOGGING_FREQ = 10000
        @Volatile
        private var lastLoggedMillis: Long = 0
    }
}
