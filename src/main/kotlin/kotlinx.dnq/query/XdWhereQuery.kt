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
import java.io.File
import java.io.InputStream


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
        return null
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
        throw unsupported()
    }

    override fun addChild(parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        throw unsupported()
    }

    private fun unsupported(): Exception = throw UnsupportedOperationException("not implemented")

}

infix fun <T : Comparable<T>> T?.less(value: T) {
    val searchingEntity = SearchingEntity.get()
    val returnType = value.javaClass.kotlin
    searchingEntity.nodes.add(PropertyRange(searchingEntity.currentProperty!!, returnType.minValue(), returnType.prev(value)))
}

infix fun <T : Comparable<T>> T?.greater(value: T) {
    val searchingEntity = SearchingEntity.get()
    val returnType = value.javaClass.kotlin
    searchingEntity.nodes.add(PropertyRange(searchingEntity.currentProperty!!, returnType.next(value), returnType.maxValue()))
}

infix fun <T : Comparable<T>> T?.between(value: kotlin.Pair<T, T>) {
    val searchingEntity = SearchingEntity.get()
    val returnType = value.first.javaClass.kotlin
    searchingEntity.nodes.add(PropertyRange(searchingEntity.currentProperty!!, returnType.prev(value.first), returnType.next(value.second)))
}

infix fun String?.startsWith(value: String?) {
    val searchingEntity = SearchingEntity.get()
    searchingEntity.nodes.add(PropertyStartsWith(searchingEntity.currentProperty!!, value ?: ""))
}

infix fun <T : Comparable<T>> T?.not(value: T) {
    val searchingEntity = SearchingEntity.get()
    searchingEntity.nodes.add(UnaryNot(PropertyEqual(searchingEntity.currentProperty!!, value)))
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
