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
package com.jetbrains.teamsys.dnq.database

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph
import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import org.apache.commons.configuration2.Configuration
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer
import org.apache.tinkerpop.gremlin.structure.*
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertex
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.*
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

class YTDBDetachedVertex(
    original: YTDBVertex,
) : DetachedVertex(original, false), YTDBVertex {

    init {
        val propertyIterator: Iterator<YTDBVertexProperty<Any>> =
            original.properties<Any>() as Iterator<YTDBVertexProperty<Any>>
        if (propertyIterator.hasNext()) {
            this.properties = HashMap<String, List<Property<*>>>()

            propertyIterator.forEachRemaining { prop: YTDBVertexProperty<Any> ->

                val list = this.properties.getOrDefault(prop.key(), mutableListOf())
                list.add(YTDBDetachedVertexProperty.detach(this, prop))
                this.properties[prop.key()] = list
            }
        }

    }

    override fun graph(): YTDBGraph = YTDBEmptyGraph

    override fun id(): RID? = id as RID?

    override fun hasProperty(key: String?): Boolean = properties?.containsKey(key) ?: false

    override fun removeProperty(key: String?): Boolean = throw Exceptions.propertyRemovalNotSupported()


    override fun <V> property(key: String): YTDBVertexProperty<V> {
        if (null == this.properties || !this.properties.containsKey(key)) {
            return YTDBVertexProperty.empty<V>()
        }

        val values = this.properties[key] as List<YTDBVertexProperty<V>>
        if (values.size > 1) {
            throw Vertex.Exceptions.multiplePropertiesExistForProvidedKey(key)
        }
        return values[0]
    }

    override fun <V> property(key: String, value: V): YTDBVertexProperty<V> {
        throw Element.Exceptions.propertyAdditionNotSupported();
    }

    override fun <V> property(key: String, value: V, vararg keyValues: Any): YTDBVertexProperty<V> {
        throw Element.Exceptions.propertyAdditionNotSupported()
    }

    override fun <V> property(
        cardinality: VertexProperty.Cardinality, key: String, value: V, vararg keyValues: Any
    ): YTDBVertexProperty<V> {
        throw Element.Exceptions.propertyAdditionNotSupported()
    }

    override fun addEdge(label: String, inVertex: Vertex, vararg keyValues: Any): YTDBEdge =
        throw Vertex.Exceptions.edgeAdditionsNotSupported()

    object Exceptions {
        fun propertyRemovalNotSupported(): IllegalStateException {
            return IllegalStateException("Property removal is not supported")
        }
    }
}

class YTDBDetachedVertexProperty<V>(
    private val id: YTDBVertexPropertyId,
    private val key: String,
    private val value: V?,
    private val vertex: YTDBVertex,
    private val type: PropertyType?
) : YTDBVertexProperty<V> {

    companion object {
        fun <T> detach(
            vertex: YTDBDetachedVertex,
            orig: YTDBVertexProperty<T>
        ): YTDBDetachedVertexProperty<T> = YTDBDetachedVertexProperty(
            id = orig.id(),
            key = orig.key(),
            value = orig.value(),
            vertex = vertex,
            type = orig.type()
        )
    }

    override fun graph(): YTDBGraph = YTDBEmptyGraph

    override fun id(): YTDBVertexPropertyId = id

    override fun element(): YTDBVertex = vertex

    override fun <U : Any> properties(vararg propertyKeys: String): Iterator<Property<U>> = Collections.emptyIterator()

    override fun key(): String = key

    override fun value(): V? = value

    override fun isPresent(): Boolean = true

    override fun remove() = throw Property.Exceptions.propertyRemovalNotSupported()

    override fun hasProperty(key: String): Boolean = false

    override fun removeProperty(key: String): Boolean = false

    override fun <V : Any> property(key: String, value: V?): YTDBProperty<V> =
        throw Element.Exceptions.propertyAdditionNotSupported()

    override fun type(): PropertyType? = type
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

    override fun backup(
        ibuFilesSupplier: Supplier<Iterator<String?>?>?,
        ibuInputStreamSupplier: Function<String?, InputStream?>?,
        ibuOutputStreamSupplier: Function<String?, OutputStream?>?,
        ibuFileRemover: Consumer<String?>?
    ) {
        throw UnsupportedOperationException("Not supported.")
    }

    override fun backup(path: Path?): String {
        throw UnsupportedOperationException("Not supported.")
    }

    override fun fullBackup(path: Path?): String {
        throw UnsupportedOperationException("Not supported.")
    }

    override fun uuid(): UUID {
        throw UnsupportedOperationException("Not supported.")
    }

    override fun <T, X : Exception> withSuspendedTransaction(supplier: org.apache.commons.lang3.function.FailableSupplier<T, X>): T {
        throw UnsupportedOperationException("Not supported.")
    }
}
