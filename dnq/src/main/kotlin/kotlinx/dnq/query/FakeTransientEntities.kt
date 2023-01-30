/**
 * Copyright 2006 - 2023 JetBrains s.r.o.
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
package kotlinx.dnq.query

import javassist.util.proxy.ProxyFactory
import jetbrains.exodus.ByteIterable
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.query.LinkEqual
import jetbrains.exodus.query.NodeBase
import jetbrains.exodus.query.PropertyEqual
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalWrapper
import kotlinx.dnq.util.XdHierarchyNode
import kotlinx.dnq.util.enclosingEntityClass
import org.joda.time.DateTime
import java.io.File
import java.io.InputStream
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.jvm.javaType


private val factoryCache = ConcurrentHashMap<String, (Entity) -> Any>()

internal open class FakeTransientEntity(protected val _type: String, protected val _entityStore: TransientEntityStore) : TransientEntity {

    companion object {
        internal val current: ThreadLocal<FakeTransientEntity> = ThreadLocal()

        fun get(): FakeTransientEntity = current.get()
    }


    override fun compareTo(other: Entity?): Int = 0

    override fun getRawProperty(propertyName: String): ByteIterable? {
        throw unsupported()
    }

    override fun getLinks(linkName: String): EntityIterable {
        throw unsupported()
    }

    override fun getLinks(linkNames: MutableCollection<String>): EntityIterable {
        throw unsupported()
    }

    override fun setBlob(blobName: String, blob: InputStream) {
        throw unsupported()
    }

    override fun setBlob(blobName: String, file: File) {
        throw unsupported()
    }

    override fun setBlobString(blobName: String, blobString: String): Boolean {
        throw unsupported()
    }

    override fun getId(): EntityId {
        throw unsupported()
    }

    override fun deleteLink(linkName: String, entity: Entity): Boolean {
        throw unsupported()
    }

    override fun deleteLink(linkName: String, targetId: EntityId): Boolean {
        throw unsupported()
    }

    override fun setLink(linkName: String, target: Entity?): Boolean {
        throw unsupported()
    }

    override fun setLink(linkName: String, targetId: EntityId): Boolean {
        throw unsupported()
    }

    override fun getProperty(propertyName: String): Comparable<Nothing>? {
        throw unsupported()
    }

    override fun getBlobNames(): MutableList<String> {
        throw unsupported()
    }

    override fun getLink(linkName: String): Entity? {
        throw unsupported()
    }

    override fun deleteLinks(linkName: String) {
        throw unsupported()
    }

    override fun getPropertyNames(): MutableList<String> {
        throw unsupported()
    }

    override fun getStore(): TransientEntityStore = _entityStore

    override fun deleteBlob(blobName: String): Boolean {
        throw unsupported()
    }

    override fun delete(): Boolean {
        throw unsupported()
    }

    override fun addLink(linkName: String, target: Entity): Boolean {
        throw unsupported()
    }

    override fun addLink(linkName: String, targetId: EntityId): Boolean {
        throw unsupported()
    }

    override fun setProperty(propertyName: String, value: Comparable<Nothing>): Boolean {
        throw unsupported()
    }

    override fun getLinkNames(): MutableList<String> {
        throw unsupported()
    }

    override fun getType(): String = _type

    override fun deleteProperty(propertyName: String): Boolean {
        throw unsupported()
    }

    override fun getBlobString(blobName: String): String? {
        throw unsupported()
    }

    override fun toIdString(): String = "fake-entity"


    override fun getBlob(blobName: String): InputStream? {
        throw unsupported()
    }

    override fun getBlobSize(blobName: String): Long {
        throw unsupported()
    }

    override fun getPropertyOldValue(propertyName: String): Comparable<Nothing> {
        throw unsupported()
    }

    override fun getAddedLinks(name: String): EntityIterable {
        throw unsupported()
    }

    override fun getAddedLinks(linkNames: Set<String>): EntityIterable {
        throw unsupported()
    }

    override val isNew get() = throw unsupported()

    override fun removeOneToMany(manyToOneLinkName: String, oneToManyLinkName: String, many: Entity) {
        throw unsupported()
    }

    override fun removeFromParent(parentToChildLinkName: String, childToParentLinkName: String) {
        throw unsupported()
    }

    override fun getLinksSize(linkName: String): Long {
        throw unsupported()
    }

    override fun clearOneToMany(manyToOneLinkName: String, oneToManyLinkName: String) {
        throw unsupported()
    }

    override val debugPresentation: String get() = throw unsupported()

    override fun hasChangesExcepting(properties: Array<String>): Boolean {
        throw unsupported()
    }

    override fun setOneToOne(e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity?) {
        throw unsupported()
    }

    override fun removeChild(parentToChildLinkName: String, childToParentLinkName: String) {
        throw unsupported()
    }

    override fun getRemovedLinks(name: String): EntityIterable {
        throw unsupported()
    }

    override fun getRemovedLinks(linkNames: Set<String>): EntityIterable {
        throw unsupported()
    }

    override val incomingLinks get() = throw unsupported()

    override fun setChild(parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        throw unsupported()
    }

    override fun createManyToMany(e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity) {
        throw unsupported()
    }

    override fun clearChildren(parentToChildLinkName: String) {
        throw unsupported()
    }

    override val isReadonly get() = true

    override val isRemoved get() = false

    override fun clearManyToMany(e1Toe2LinkName: String, e2Toe1LinkName: String) {
        throw unsupported()
    }

    override val isWrapper get() = false

    override val isSaved get() = true

    override fun hasChanges() = false

    override fun hasChanges(property: String) = false

    override val parent get() = throw unsupported()

    override val persistentEntity get() = throw unsupported()

    override fun setManyToOne(manyToOneLinkName: String, oneToManyLinkName: String, one: Entity?) {
        throw unsupported()
    }

    override fun setToOne(linkName: String, target: Entity?) {
        throw unsupported()
    }

    override fun addChild(parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        throw unsupported()
    }

    private fun unsupported(): Exception = UnsupportedOperationException("not implemented")

    internal fun XdHierarchyNode.findProperty(name: String): XdHierarchyNode.SimpleProperty? {
        val result = simpleProperties.values.firstOrNull { it.dbPropertyName == name }
        if (result == null) {
            if (parentNode != null) {
                return parentNode.findProperty(name)
            }
            return null
        }
        return result
    }

    protected fun XdHierarchyNode.findLink(name: String): XdHierarchyNode.LinkProperty? {
        val result = linkProperties.values.firstOrNull { it.dbPropertyName == name }
        if (result == null) {
            if (parentNode != null) {
                return parentNode.findLink(name)
            }
            return null
        }
        return result
    }

    fun <T : XdEntity> toXdHandlingAbstraction(): T {
        val node = XdModel.getOrThrow(type)
        val entityType = node.entityType
        if (entityType is XdNaturalWrapper) {
            @Suppress("UNCHECKED_CAST")
            return entityType.naturalWrap(this) as T
        }
        val entityConstructor = node.entityConstructor
        // this is abstract type. lets create fake implementation.
        if (entityConstructor == null) {
            val constructor = factoryCache.getOrPut(type) {
                val factory = ProxyFactory().apply {
                    superclass = node.entityType.enclosingEntityClass
                    setFilter({ method -> Modifier.isAbstract(method.modifiers) })
                }
                val c = factory.createClass()
                val cons = c.getConstructor(Entity::class.java)

                val ctor = { entity: Entity ->
                    cons.newInstance(entity)
                }
                ctor

            }
            @Suppress("UNCHECKED_CAST")
            return constructor(this) as T
        }
        @Suppress("UNCHECKED_CAST")
        return entityConstructor(this) as T
    }

}

internal class SearchingEntity(_type: String, _entityStore: TransientEntityStore) : FakeTransientEntity(_type, _entityStore) {

    companion object {
        fun get(): SearchingEntity = current.get() as SearchingEntity
    }

    var currentNodeName: String? = null
    val nodes: MutableList<NodeBase> = arrayListOf()
    var parentEntity: SearchingEntity? = null
    var childEntity: SearchingEntity? = null

    private fun SearchingEntity.bindAsChild(): SearchingEntity {
        parentEntity = this@SearchingEntity
        this@SearchingEntity.childEntity = this
        return this
    }

    fun deepestChild(): SearchingEntity {
        val entity = childEntity
        if (entity?.currentNodeName != null) {
            return entity.deepestChild()
        }
        return this
    }

    override fun deleteLink(linkName: String, entity: Entity): Boolean {
        currentNodeName = linkName
        addToNodes(LinkEqual(linkName, null).decorateIfNeeded())
        return true
    }

    override fun setLink(linkName: String, target: Entity?): Boolean {
        currentNodeName = linkName
        addToNodes(LinkEqual(linkName, target).decorateIfNeeded())
        return true
    }

    override fun getProperty(propertyName: String): Comparable<Nothing>? {
        currentNodeName = propertyName
        return getDefaultPropertyValue(propertyName)
    }

    override fun getLink(linkName: String): Entity? {
        currentNodeName = linkName
        val node = XdModel.getOrThrow(_type)
        node.findLink(linkName).let {
            it ?: return null
            return SearchingEntity(it.delegate.oppositeEntityType.entityType, _entityStore).bindAsChild()
        }
    }

    override fun getLinks(linkName: String): EntityIterable {
        currentNodeName = linkName
        return EntityIterableBase.EMPTY
    }

    override fun setProperty(propertyName: String, value: Comparable<Nothing>): Boolean {
        currentNodeName = propertyName
        addToNodes(PropertyEqual(propertyName, value).decorateIfNeeded())
        return true
    }

    override fun deleteProperty(propertyName: String): Boolean {
        currentNodeName = propertyName
        addToNodes(PropertyEqual(propertyName, null).decorateIfNeeded())
        return true
    }

    override fun setManyToOne(manyToOneLinkName: String, oneToManyLinkName: String, one: Entity?) {
        currentNodeName = manyToOneLinkName
        addToNodes(LinkEqual(manyToOneLinkName, one).decorateIfNeeded())
    }

    override fun setToOne(linkName: String, target: Entity?) {
        currentNodeName = linkName
        addToNodes(LinkEqual(linkName, target).decorateIfNeeded())
    }

    private fun getDefaultPropertyValue(propertyName: String): Comparable<Nothing>? {
        val node = XdModel.getOrThrow(_type)
        node.findProperty(propertyName).let {
            val simpleProperty = it ?: return null
            return when (simpleProperty.property.returnType.javaType) {
                java.lang.String::class.java -> ""

                java.lang.Boolean.TYPE -> false
                java.lang.Boolean::class.java -> false

                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Byte::class.java -> 0.toByte()

                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Short::class.java -> 0.toShort()

                java.lang.Character.TYPE -> 0.toChar()
                java.lang.Character::class.java -> 0.toChar()

                java.lang.Integer.TYPE -> 0
                java.lang.Integer::class.java -> 0

                java.lang.Long.TYPE -> 0.toLong()
                java.lang.Long::class.java -> 0.toLong()

                DateTime::class.java -> 0.toLong()

                java.lang.Float.TYPE -> 0.toFloat()
                java.lang.Float::class.java -> 0.toFloat()

                java.lang.Double.TYPE -> 0.toDouble()
                java.lang.Double::class.java -> 0.toDouble()
                else -> {
                    ""
                }
            }
        }
    }

    private fun addToNodes(nodeBase: NodeBase) {
        SearchingEntity.get().nodes.add(nodeBase)
    }

}

internal class MappingEntity(_type: String, _entityStore: TransientEntityStore) : FakeTransientEntity(_type, _entityStore) {

    var link: XdHierarchyNode.LinkProperty? = null

    override fun getLink(linkName: String): Entity? {
        val node = XdModel.getOrThrow(_type)
        node.findLink(linkName).let {
            link = it ?: throw IllegalStateException("can't found model name for $linkName")
            return MappingEntity(it.delegate.oppositeEntityType.entityType, _entityStore)
        }
    }

    override fun getLinks(linkName: String): EntityIterable {
        val node = XdModel.getOrThrow(_type)
        node.findLink(linkName).let {
            link = it ?: throw IllegalStateException("can't found model name for $linkName")
            return EntityIterableBase.EMPTY
        }
    }

}

