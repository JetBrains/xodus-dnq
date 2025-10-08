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
package jetbrains.exodus.entitystore.youtrackdb

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.api.record.RID
import org.apache.commons.configuration2.Configuration
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer
import org.apache.tinkerpop.gremlin.structure.*
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertex
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph

class YTDBDetachedVertex(original: YTDBVertex) : DetachedVertex(original, true), YTDBVertex {

    override fun graph(): YTDBGraph = YTDBEmptyGraph

    override fun id(): RID? = id as RID?

    override fun hasProperty(key: String?): Boolean = properties?.containsKey(key) ?: false

    override fun removeProperty(key: String?): Boolean = throw Exceptions.propertyRemovalNotSupported()

    override fun addEdge(label: String, inVertex: Vertex, vararg keyValues: Any): YTDBEdge =
        throw Vertex.Exceptions.edgeAdditionsNotSupported()

    object Exceptions {
        fun propertyRemovalNotSupported(): IllegalStateException {
            return IllegalStateException("Property removal is not supported")
        }
    }
}

object YTDBEmptyGraph : YTDBGraph {
    private val STD_INSTANCE: Graph = EmptyGraph.instance()

    override fun features(): Graph.Features {
        return STD_INSTANCE.features()
    }

    override fun addVertex(vararg keyValues: Any): YTDBVertex {
        return STD_INSTANCE.addVertex(*keyValues) as YTDBVertex
    }

    override fun addVertex(label: String?): YTDBVertex {
        return STD_INSTANCE.addVertex(label) as YTDBVertex
    }

    override fun <C : GraphComputer> compute(graphComputerClass: Class<C>): C {
        return STD_INSTANCE.compute<C>(graphComputerClass)
    }

    override fun compute(): GraphComputer {
        return STD_INSTANCE.compute()
    }

    override fun tx(): Transaction {
        return STD_INSTANCE.tx()
    }

    override fun variables(): Graph.Variables {
        return STD_INSTANCE.variables()
    }

    override fun configuration(): Configuration {
        return STD_INSTANCE.configuration()
    }

    override fun close() {
        STD_INSTANCE.close()
    }

    override fun vertices(vararg vertexIds: Any): MutableIterator<Vertex> {
        return STD_INSTANCE.vertices(*vertexIds)
    }

    override fun edges(vararg edgeIds: Any): MutableIterator<Edge> {
        return STD_INSTANCE.edges(*edgeIds)
    }

    override fun toString(): String {
        return STD_INSTANCE.toString()
    }
}
