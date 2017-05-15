package kotlinx.dnq.query

import jetbrains.exodus.ByteIterable
import jetbrains.exodus.core.dataStructures.Pair
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntity
import jetbrains.exodus.query.*
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.XdModel
import kotlinx.dnq.util.XdHierarchyNode
import org.joda.time.DateTime
import java.io.File
import java.io.InputStream
import kotlin.reflect.jvm.javaType

fun <T : XdEntity> XdQuery<T>.filter(clause: (T) -> Unit): XdQuery<T> {
    val searchingEntity = SearchingEntity(entityType.entityType, entityType.entityStore).inScope {
        clause(entityType.wrap(this))
    }
    return searchingEntity.nodes.fold(this) { cur, it ->
        cur.query(it)
    }
}

fun <T : XdEntity> XdEntityType<T>.filter(clause: (T) -> Unit): XdQuery<T> {
    return all().filter(clause)
}

class SearchingEntity(private val _type: String, private val _entityStore: TransientEntityStore) : TransientEntity {

    companion object {
        internal val current: ThreadLocal<SearchingEntity> = ThreadLocal()

        fun get(): SearchingEntity = current.get()
    }

    var currentProperty: String? = null
    val nodes: MutableList<NodeBase> = arrayListOf()

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
        nodes.add(LinkEqual(linkName, null))
        return true
    }

    override fun setLink(linkName: String, target: Entity?): Boolean {
        nodes.add(LinkEqual(linkName, target))
        return true
    }

    override fun getProperty(propertyName: String): Comparable<Nothing>? {
        currentProperty = propertyName
        val node = XdModel.getOrThrow(_type)
        node.findProperty(propertyName).let {
            val simpleProperty = it ?: return 0
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

    override fun getBlobNames(): MutableList<String> {
        throw unsupported()
    }

    override fun getLink(linkName: String): Entity? {
        currentProperty = linkName
        val node = XdModel.getOrThrow(_type)
        node.findLink(linkName).let {
            it ?: return this
            return SearchingEntity(it.delegate.oppositeEntityType.entityType, _entityStore)
        }
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

    override fun setProperty(propertyName: String, value: Comparable<Nothing>): Boolean {
        nodes.add(PropertyEqual(propertyName, value))
        return true
    }

    override fun getLinkNames(): MutableList<String> {
        throw unsupported()
    }

    override fun getType(): String = _type

    override fun deleteProperty(propertyName: String): Boolean {
        nodes.add(PropertyEqual(propertyName, null))
        return true
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

    override fun getAddedLinks(name: String?): EntityIterable {
        throw unsupported()
    }

    override fun getAddedLinks(linkNames: MutableSet<String>?): EntityIterable {
        throw unsupported()
    }

    override fun isNew(): Boolean {
        throw unsupported()
    }

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

    override fun getDebugPresentation(): String {
        throw unsupported()
    }

    override fun hasChangesExcepting(properties: Array<out String>?): Boolean {
        throw unsupported()
    }

    override fun setOneToOne(e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity?) {
        throw unsupported()
    }

    override fun removeChild(parentToChildLinkName: String, childToParentLinkName: String) {
        throw unsupported()
    }

    override fun getRemovedLinks(name: String?): EntityIterable {
        throw unsupported()
    }

    override fun getRemovedLinks(linkNames: MutableSet<String>?): EntityIterable {
        throw unsupported()
    }

    override fun getIncomingLinks(): MutableList<Pair<String, EntityIterable>> {
        throw unsupported()
    }

    override fun setChild(parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        throw unsupported()
    }

    override fun createManyToMany(e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity) {
        throw unsupported()
    }

    override fun clearChildren(parentToChildLinkName: String) {
        throw unsupported()
    }

    override fun isReadonly(): Boolean = true

    override fun isRemoved(): Boolean = false

    override fun clearManyToMany(e1Toe2LinkName: String, e2Toe1LinkName: String) {
        throw unsupported()
    }

    override fun isWrapper(): Boolean = false

    override fun isSaved(): Boolean = true

    override fun hasChanges(): Boolean = false

    override fun hasChanges(property: String?): Boolean = false

    override fun getParent(): Entity {
        throw unsupported()
    }

    override fun getPersistentEntity(): PersistentEntity {
        throw unsupported()
    }

    override fun setManyToOne(manyToOneLinkName: String, oneToManyLinkName: String, one: Entity?) {
        nodes.add(LinkEqual(manyToOneLinkName, one))
    }

    override fun setToOne(linkName: String, target: Entity?) {
        currentProperty = linkName
        nodes.add(LinkEqual(linkName, target))
    }

    override fun addChild(parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        throw unsupported()
    }

    private fun unsupported(): Exception = UnsupportedOperationException("not implemented")

    private fun XdHierarchyNode.findProperty(name: String): XdHierarchyNode.SimpleProperty? {
        val result = simpleProperties.values.firstOrNull { it.dbPropertyName == name }
        if (result == null) {
            if (parentNode != null) {
                return parentNode.findProperty(name)
            }
            return null
        }
        return result
    }

    private fun XdHierarchyNode.findLink(name: String): XdHierarchyNode.LinkProperty? {
        val result = linkProperties.values.firstOrNull { it.dbPropertyName == name }
        if (result == null) {
            if (parentNode != null) {
                return parentNode.findLink(name)
            }
            return null
        }
        return result
    }
}

infix fun <T : Comparable<T>> T?.lt(value: T): XdSearchingNode {
    val returnType = value.javaClass.kotlin
    return withNode(PropertyRange(SearchingEntity.get().currentProperty!!, returnType.minValue(), returnType.prev(value)))
}

infix fun <T : Comparable<T>> T?.eq(value: T?): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    val correctedValue = value?.let {
        if (it is DateTime) {
            it.millis
        } else {
            it
        }
    }
    return withNode(PropertyEqual(searchingEntity.currentProperty!!, correctedValue))
}

infix fun <T : XdEntity> T?.eq(value: T?): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    return withNode(LinkEqual(searchingEntity.currentProperty!!, value?.entity))
}

infix fun <T : Comparable<T>> T?.gt(value: T): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    val returnType = value.javaClass.kotlin
    return withNode(PropertyRange(searchingEntity.currentProperty!!, returnType.next(value), returnType.maxValue()))
}

infix fun <T : Comparable<T>> T?.between(value: kotlin.Pair<T, T>): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    val returnType = value.first.javaClass.kotlin
    return withNode(PropertyRange(searchingEntity.currentProperty!!, returnType.prev(value.first), returnType.next(value.second)))
}

infix fun String?.startsWith(value: String?): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    return withNode(PropertyStartsWith(searchingEntity.currentProperty!!, value ?: ""))
}

infix fun <T : Comparable<T>> T?.ne(value: T?): XdSearchingNode {
    return withNode(UnaryNot(PropertyEqual(SearchingEntity.get().currentProperty!!, value)))
}

infix fun <T : XdEntity> T?.isIn(entities: Iterable<T?>): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    return withNode(entities.fold(None as NodeBase) { tree, e -> tree or (LinkEqual(searchingEntity.currentProperty!!, e?.entity)) })
}

infix fun <T : Comparable<*>> T?.isIn(values: Iterable<T?>): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    return withNode(values.fold(None as NodeBase) { tree, v -> tree or (PropertyEqual(searchingEntity.currentProperty!!, v)) })
}

private fun withNode(node: NodeBase): XdSearchingNode {
    return node.let {
        SearchingEntity.get().nodes.add(it)
        XdSearchingNode(it)
    }
}

fun SearchingEntity.inScope(fn: SearchingEntity.() -> Unit): SearchingEntity {
    SearchingEntity.current.set(this)
    try {
        fn()
    } finally {
        SearchingEntity.current.set(null)
    }
    return this
}

open class XdSearchingNode(val target: NodeBase) {

    infix open fun and(another: XdSearchingNode): XdSearchingNode {
        return process(another) { And(target, another.target) }
    }

    infix open fun or(another: XdSearchingNode): XdSearchingNode {
        return process(another) { Or(target, another.target) }
    }

    private fun process(another: XdSearchingNode,
                        factory: () -> CommutativeOperator): XdSearchingNode {
        return XdSearchingNode(factory()).also {
            SearchingEntity.get().nodes.apply {
                removeAll(listOf(target, another.target))
                add(it.target)
            }
        }
    }
}