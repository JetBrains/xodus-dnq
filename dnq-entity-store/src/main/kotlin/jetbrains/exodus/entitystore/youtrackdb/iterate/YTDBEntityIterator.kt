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
package jetbrains.exodus.entitystore.youtrackdb.iterate

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterator
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityStore
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import java.lang.AutoCloseable

class YTDBEntityIterator(
    private val vertices: Iterator<YTDBVertexEntity>,
    private var closed: Boolean = false,
    private val disposeResources: () -> Unit = {},
) : EntityIterator, AutoCloseable {

    companion object {
        val EMPTY = YTDBEntityIterator(emptyList<YTDBVertexEntity>().iterator(), closed = true)

        @JvmStatic
        fun empty() = EMPTY

        fun of(traversal: GraphTraversal<*, YTDBVertex>, store: YTDBEntityStore) = YTDBEntityIterator(
            vertices = traversal.iterator().asSequence().map {
                YTDBVertexEntity(it, store)
            }.iterator(),
            closed = false,
            disposeResources = { traversal.close() }
        )

    }

    /**
     * Skips up to [number] entities and returns the value of [hasNext], per the
     * [EntityIterator.skip] contract. Note [hasNext] disposes the traversal on exhaustion, so
     * `skip(n)` that runs off the end closes it — a divergence from Xodus's `EntityIteratorBase.skip`,
     * which drives the walk with the non-disposing `hasNextImpl()`.
     */
    override fun skip(number: Int): Boolean {
        repeat(number) {
            if (!hasNext()) {
                return false
            }
            next()
        }
        return hasNext()
    }

    override fun nextId(): EntityId = next().id

    override fun dispose(): Boolean {
        if (closed) {
            return false
        }
        closed = true
        disposeResources()
        return true
    }

    override fun shouldBeDisposed(): Boolean = !closed

    override fun remove() = throw UnsupportedOperationException()

    override fun hasNext(): Boolean {
        val hasNext = vertices.hasNext()

        if (!hasNext) dispose()

        return hasNext
    };

    // todo: special TimeoutException handling?
    override fun next(): Entity = vertices.next();

    override fun close() {
        dispose()
    }
}