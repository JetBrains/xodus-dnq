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

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.record.TrackedMultiValue
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexInternal
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.record.impl.RecordBytes
import jetbrains.exodus.ByteIterable
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.CLASS_ID_CUSTOM_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.LOCAL_ENTITY_ID_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.linkTargetEntityIdPropertyName
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBVertexEntityIterable
import jetbrains.exodus.util.LightByteArrayOutputStream
import jetbrains.exodus.util.UTFUtil
import mu.KLogging
import org.apache.tinkerpop.gremlin.structure.Direction
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

open class YTDBVertexEntity(
    private var oEntityId: RIDEntityId,
    private var ytdbVertex: YTDBVertex,
    private val store: YTDBEntityStore
) : YTDBEntity {

    companion object : KLogging() {
        const val EDGE_CLASS_SUFFIX = "_link"
        private const val LINK_TARGET_ENTITY_ID_PROPERTY_NAME_SUFFIX = "_targetEntityId"
        private const val BLOB_SIZE_PROPERTY_NAME_SUFFIX = "_blob_size"
        private const val STRING_BLOB_HASH_PROPERTY_NAME_SUFFIX = "_string_blob_hash"
        fun blobSizeProperty(propertyName: String) =
            "_$propertyName$BLOB_SIZE_PROPERTY_NAME_SUFFIX"

        fun blobHashProperty(propertyName: String) =
            "_$propertyName$STRING_BLOB_HASH_PROPERTY_NAME_SUFFIX"

        // Backward compatible EntityId

        const val CLASS_ID_CUSTOM_PROPERTY_NAME = "classId"
        const val CLASS_ID_SEQUENCE_NAME = "sequence_classId"

        const val LOCAL_ENTITY_ID_PROPERTY_NAME = "localEntityId"
        val IGNORED_PROPERTY_NAMES = setOf(LOCAL_ENTITY_ID_PROPERTY_NAME)
        val IGNORED_SUFFIXES = setOf(
            LINK_TARGET_ENTITY_ID_PROPERTY_NAME_SUFFIX,
            BLOB_SIZE_PROPERTY_NAME_SUFFIX,
            STRING_BLOB_HASH_PROPERTY_NAME_SUFFIX
        )

        fun validPropertyNamesPredicate(entity: YTDBVertexEntity): (String) -> Boolean {
            return validPropertyNamesPredicate(entity.allPropertyNames().toSet())
        }

        private fun validPropertyNamesPredicate(propertyNames: Collection<String>): (String) -> Boolean {
            val allPropertiesNames = propertyNames.toSet()
            return { propName ->
                //not ignored properties
                !IGNORED_PROPERTY_NAMES.contains(propName)
                        && !allPropertiesNames.contains(blobHashProperty(propName))
                        && !allPropertiesNames.contains(blobSizeProperty(propName))
                        && !IGNORED_SUFFIXES.any { suffix -> propName.endsWith(suffix) }
            }
        }


        fun localEntityIdSequenceName(className: String): String =
            "${className}_sequence_localEntityId"

        fun edgeClassName(className: String): String {
            // YouTrack has fancy link names like '__CUSTOM_FIELD__Country/Region_227'. OrientDB does not like symbols
            // like '/' in class names. So we have to get rid of them.
            val sanitizedClassName = className.replace('/', '_')
            return "$sanitizedClassName$EDGE_CLASS_SUFFIX"
        }

        fun linkTargetEntityIdPropertyName(linkName: String): String {
            return "$linkName$LINK_TARGET_ENTITY_ID_PROPERTY_NAME_SUFFIX"
        }
    }

    val vertex: YTDBVertex get() = ytdbVertex

    constructor(
        ytdbVertex: YTDBVertex,
        store: YTDBEntityStore
    ) : this(RIDEntityId.fromVertex(ytdbVertex), ytdbVertex, store)

    override fun getStore() = store

    override fun getId(): YTDBEntityId = oEntityId

    override fun toIdString(): String = oEntityId.toString()

    override fun getType(): String = oEntityId.getTypeName()

    override fun delete(): Boolean {
        requireActiveWritableTransaction().deleteVertex(ytdbVertex.id())
        return true
    }

    override fun resetToNew() {
        val className = oEntityId.getTypeName()
        ytdbVertex = store.requireActiveTransaction().newVertex(className)
    }

    override fun generateId(localId: Long?) {
        val type = oEntityId.getTypeName()
        if (localId != null) {
            safeVertex { property(LOCAL_ENTITY_ID_PROPERTY_NAME, localId) }
        } else {
            store.requireActiveTransaction().generateEntityId(type, ytdbVertex)
        }

        oEntityId = RIDEntityId.fromVertex(ytdbVertex)
    }

    fun requireActiveTx(): YTDBStoreTransaction {
        return store.requireActiveTransaction()
    }

    override fun getRawProperty(propertyName: String): ByteIterable? {
        requireActiveTx()
        TODO()
    }

    override fun getProperty(propertyName: String): Comparable<*>? {
        requireActiveTx()
        val propValue = safeVertex { property<Any>(propertyName) }
        return if (!propValue.isPresent) null
        else if (propValue.value() is MutableSet<*>) YTDBComparableSet(propValue.value() as MutableSet<*>)
        else propValue.value() as Comparable<*>
    }

    override fun setProperty(propertyName: String, value: Comparable<*>): Boolean {
        requireActiveWritableTransaction()
        val propValue = safeVertex { property<Any>(propertyName) }

        if (propValue.isPresent) {
            if (value is MutableSet<*> || propValue.value() is MutableSet<*>) {
                return setPropertyAsSet(propertyName, value)
            } else if (propValue.value() == value) {
                return false
            }
        }

        safeVertex { property(propertyName, value) }
        return true
    }

    private fun setPropertyAsSet(propertyName: String, newValue: Any?): Boolean {
        val set = when (newValue) {
            is YTDBComparableSet<*> -> newValue.source
            is MutableSet<*> -> newValue
            else -> throw IllegalArgumentException("Unexpected value: $newValue")
        }
        if (set is TrackedMultiValue<*, *> && set.owner === safeVertex { raw() })
            safeVertex { property(propertyName, set) }
        else if (set.firstOrNull() is Identifiable)
            @Suppress("UNCHECKED_CAST")
            safeVertex { raw() }.newLinkSet(propertyName, set as MutableSet<Identifiable>)
        else
            safeVertex { raw() }.newEmbeddedSet(propertyName, set)

        val propValue = safeVertex { property<TrackedMultiValue<*, *>>(propertyName) }
        return propValue.isPresent && propValue.value().isTransactionModified
    }

    override fun deleteProperty(propertyName: String): Boolean {
        requireActiveWritableTransaction()
        if (safeVertex { hasProperty(propertyName) }) {
            safeVertex { removeProperty(propertyName) }
            return true
        } else {
            return false
        }
    }

    private fun allPropertyNames() = safeVertex { properties<Any>() }.asSequence().map { it.key() }

    override fun getPropertyNames(): List<String> {
        requireActiveTx()
        val allPropertiesNames = allPropertyNames().toList()
        val predicate = validPropertyNamesPredicate(allPropertiesNames)
        return allPropertiesNames
            .filter(predicate)
            .toList()
    }

    override fun getBlob(blobName: String): InputStream? {
        requireActiveTx()
        val blob = safeVertex { property<RecordBytes>(blobName) }.orElse(null) ?: return null
        return ByteArrayInputStream(blob.toStream())
    }

    override fun getBlobSize(blobName: String): Long {
        requireActiveTx()

        return safeVertex { property<Long>(blobSizeProperty(blobName)) }.orElse(null) ?: -1
    }

    override fun setBlob(blobName: String, blob: InputStream) {
        requireActiveWritableTransaction()

        if (safeVertex { hasProperty(blobName) }) {
            safeVertex { removeProperty(blobName) }
        }

        val allBytes = blob.readAllBytes()
        val oBlob = store.requireActiveTransaction().newBlob(allBytes)
        safeVertex { property(blobName, oBlob) }
        safeVertex { property(blobSizeProperty(blobName), allBytes.size.toLong()) }
    }

    override fun deleteBlob(blobName: String): Boolean {
        requireActiveWritableTransaction()
        if (safeVertex { hasProperty(blobName) }) {
            safeVertex { removeProperty(blobName) }
            safeVertex { removeProperty(blobSizeProperty(blobName)) }
            safeVertex { removeProperty(blobHashProperty(blobName)) }
            return true
        }
        return false
    }

    override fun getBlobString(blobName: String): String? {
        requireActiveTx()
        val blob = safeVertex { property<RecordBytes>(blobName) }.orElse(null) ?: return null
        return UTFUtil.readUTF(ByteArrayInputStream(blob.toStream()))
    }

    override fun setBlob(blobName: String, file: File) {
        setBlob(blobName, file.inputStream())
    }

    /**
     * Stores the string in the modified UTF-8 format
     */
    override fun setBlobString(blobName: String, blobString: String): Boolean {
        requireActiveWritableTransaction()

        // toByteArray() will not copy data
        val baos = LightByteArrayOutputStream(blobString.length)
        UTFUtil.writeUTF(baos, blobString)

        // we know the exact size only when we encoded the string to UTF.
        // so, here we can check if we already have the same one
        if (safeVertex { hasProperty(blobName) }) {
            val oldHash = safeVertex { property<Int>(blobHashProperty(blobName)) }.value()
            val oldLen = safeVertex { property<Long>(blobSizeProperty(blobName)) }.value()
            if (oldHash == blobString.hashCode() && oldLen == baos.size().toLong()) {
                return false
            }
            safeVertex { removeProperty(blobName) }
        }

        val oBlob = store.requireActiveTransaction().newBlob(baos.toByteArray())
        safeVertex { property(blobName, oBlob) }
        safeVertex { property(blobHashProperty(blobName), blobString.hashCode()) }
        safeVertex { property(blobSizeProperty(blobName), baos.size().toLong()) }
        return true
    }

    override fun getBlobNames(): List<String> {
        requireActiveTx()
        return allPropertyNames()
            .filter { it.endsWith(BLOB_SIZE_PROPERTY_NAME_SUFFIX) }
            .map { it.substring(1).substringBefore(BLOB_SIZE_PROPERTY_NAME_SUFFIX) }
            .toList()
    }

    // Add links

    override fun addLink(linkName: String, target: Entity): Boolean {
        val currentTx = requireActiveWritableTransaction()
        require(target is YTDBVertexEntity) { "Only OVertexEntity is supported, but was ${target.javaClass.simpleName}" }
        return currentTx.addLinkImpl(linkName, target.ytdbVertex)
    }

    override fun addLink(linkName: String, targetId: EntityId): Boolean {
        val currentTx = requireActiveWritableTransaction()
        val targetOId = store.resolveEntityIdOrNull(targetId) ?: return false
        try {
            val target = currentTx.getVertex(targetOId)
            return currentTx.addLinkImpl(linkName, target)
        } catch (e: EntityRemovedInDatabaseException) {
            return false
        }
    }

    private fun YTDBStoreTransaction.addLinkImpl(linkName: String, target: YTDBVertex): Boolean {
        val outClassName = safeVertex { label() }
        val inClassName = target.label()
        val edgeClass = getOrCreateEdgeClass(linkName, outClassName, inClassName)
        val edgeClassName = edgeClassName(linkName)

        /*
        We check for duplicates only if there is an appropriate index for it.
        Without an index, performance degradation will be catastrophic.

        You may ask why not to throw an exception if there is no an index?
        Well, we have the data migration process. During this process:
        1. We do not have any indices
        2. Skipping this findEdge(...) call is exactly what we want from the performance point of view.
        3. We avoid duplicates explicitly.
        Well, during the data migration process, there are no any indices and
        skipping this findEdge(...) call is exactly what we need.
         */
        val currentEdge: YTDBEdge? =
            if ((edgeClass as SchemaClassInternal).areIndexed(
                    safeVertex { raw() }.boundedToSession as DatabaseSessionEmbedded,
                    Edge.DIRECTION_IN,
                    Edge.DIRECTION_OUT
                )
            ) {
                findEdge(edgeClassName, ytdbVertex.id(), target.id())
            } else null

        if (currentEdge == null) {
            safeVertex { addEdge(edgeClassName, target) }
            // If the link is indexed, we have to update the complementary internal property.
            safeVertex { addTargetEntityIdIfLinkIndexed(linkName, target.id()) }
            return true
        } else {
            return false
        }
    }

    private fun YTDBVertex.addTargetEntityIdIfLinkIndexed(linkName: String, targetId: RID) {
        val linkTargetEntityIdPropertyName = linkTargetEntityIdPropertyName(linkName)
        if (requireSchemaClass().existsProperty(linkTargetEntityIdPropertyName)) {
            val bag = property<LinkBag>(linkTargetEntityIdPropertyName).orElse(null)
                ?: LinkBag(raw().boundedToSession as DatabaseSessionEmbedded)
            bag.add(targetId)
            property(linkTargetEntityIdPropertyName, bag)
        }
    }


    // Delete links

    override fun deleteLink(linkName: String, target: Entity): Boolean {
        val currentTx = requireActiveWritableTransaction()
        target as YTDBVertexEntity
        val targetOId = target.oEntityId.asOId()
        return currentTx.deleteLinkImpl(linkName, targetOId)
    }

    override fun deleteLink(linkName: String, targetId: EntityId): Boolean {
        val currentTx = requireActiveWritableTransaction()
        val targetOId = store.resolveEntityIdOrNull(targetId)?.asOId() ?: return false
        return currentTx.deleteLinkImpl(linkName, targetOId)
    }

    override fun deleteLinks(linkName: String) {
        val tx = requireActiveWritableTransaction()
        val edgeClassName = edgeClassName(linkName)
        safeVertex { edges(Direction.OUT, edgeClassName) }.forEach {
            tx.deleteEdge(it.id() as RID)
        }
        safeVertex { deleteAllTargetEntityIdsIfLinkIndexed(linkName) }
    }

    private fun YTDBStoreTransaction.deleteLinkImpl(linkName: String, targetId: RID): Boolean {
        val edgeClassName = edgeClassName(linkName)

        val edge = findEdge(edgeClassName, ytdbVertex.id(), targetId)
        if (edge != null) {
            deleteEdge(edge.id() as RID)
            // if the link in a composite index, we have to update the complementary internal property.
            safeVertex { deleteTargetEntityIdIfLinkIndexed(linkName, targetId) }
            return true
        }

        return false
    }

    private fun YTDBVertex.deleteTargetEntityIdIfLinkIndexed(linkName: String, targetId: RID) {
        val linkTargetEntityIdPropertyName = linkTargetEntityIdPropertyName(linkName)
        if (requireSchemaClass().existsProperty(linkTargetEntityIdPropertyName)) {
            val bag = property<LinkBag>(linkTargetEntityIdPropertyName).orElse(null)
                ?: LinkBag(raw().boundedToSession as DatabaseSessionEmbedded)
            bag.remove(targetId)
            property(linkTargetEntityIdPropertyName, bag)
        }
    }

    private fun YTDBVertex.deleteAllTargetEntityIdsIfLinkIndexed(linkName: String) {
        val propName = linkTargetEntityIdPropertyName(linkName)
        if (requireSchemaClass().existsProperty(propName)) {
            property(propName, LinkBag(raw().boundedToSession as DatabaseSessionEmbedded))
        }
    }


    // Set links

    override fun setLink(linkName: String, target: Entity?): Boolean {
        val currentTx = requireActiveWritableTransaction()
        require(target is YTDBVertexEntity?) { "Only OVertexEntity is supported, but was ${target?.javaClass?.simpleName}" }
        return currentTx.setLinkImpl(linkName, target?.ytdbVertex)
    }

    override fun setLink(linkName: String, targetId: EntityId): Boolean {
        val currentTx = requireActiveWritableTransaction()
        val targetOId = store.resolveEntityIdOrNull(targetId) ?: return false
        try {
            val target = currentTx.getVertex(targetOId)
            return currentTx.setLinkImpl(linkName, target)
        } catch (e: EntityRemovedInDatabaseException) {
            return false
        }
    }

    private fun YTDBStoreTransaction.setLinkImpl(linkName: String, target: YTDBVertex?): Boolean {
        val currentLink = getLinkImpl(linkName)

        if (currentLink == target) {
            return false
        }
        if (currentLink != null) {
            deleteLinkImpl(linkName, currentLink.id())
        }
        if (target != null) {
            addLinkImpl(linkName, target)
        }
        return true
    }

    // Get links

    override fun getLink(linkName: String): Entity? {
        requireActiveTx()
        return getLinkImpl(linkName).toOEntityOrNull()
    }

    private fun getLinkImpl(linkName: String): YTDBVertex? {
        val edgeClassName = edgeClassName(linkName)
        return safeVertex { vertices(Direction.OUT, edgeClassName) }.asSequence().firstOrNull() as? YTDBVertex
    }

    override fun getLinks(linkName: String): EntityIterable {
        val txn = requireActiveTx()
        val edgeClassName = edgeClassName(linkName)
        val links = safeVertex { vertices(Direction.OUT, edgeClassName) }
            .asSequence()
            .map { it as YTDBVertex }
            .toList()
        return YTDBVertexEntityIterable(txn, links, store, linkName, this.oEntityId)
    }

    //todo this method should return iterable of different type
    override fun getLinks(linkNames: Collection<String>): EntityIterable {
        requireActiveTx()
        val tx = requireActiveTx()
        return if (linkNames.size == 1) {
            getLinks(linkNames.first())
        } else {
            // todo: Gremlin supports querying multiple links at once, rewrite this query
            YTDBEntityIterable.query(
                tx.getStore(),
                linkNames.asSequence()
                    .map { ln ->
                        GremlinQuery.ByIds(listOf(this.oEntityId.asOId()))
                            .then(GremlinBlock.OutLink(ln))
                    }
                    .reduce { acc, it -> acc.union(it) }
            )
        }
    }

    override fun getLinkNames(): List<String> {
        requireActiveTx()
        // how to get all edge names from a vertex using tinkerpop api?
        return ArrayList(
            safeVertex { raw() }
                .getEdgeNames(com.jetbrains.youtrackdb.internal.core.db.record.record.Direction.OUT)
                .filter { it.endsWith(EDGE_CLASS_SUFFIX) }
                .map { it.substringAfter(Vertex.DIRECTION_OUT_PREFIX).substringBefore(EDGE_CLASS_SUFFIX) })
    }

    override fun compareTo(other: Entity) = id.compareTo(other.id)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is YTDBEntity) return false
        if (javaClass != other.javaClass) return false

        return this.id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    protected open fun requireActiveWritableTransaction(): YTDBStoreTransaction {
        return store.requireActiveWritableTransaction()
    }

    private fun YTDBVertex?.toOEntityOrNull(): YTDBEntity? = this?.let { YTDBVertexEntity(this, store) }

    private inline fun <T> safeVertex(block: YTDBVertex.() -> T): T {
        try {
            return block(ytdbVertex)
        } catch (rnf: RecordNotFoundException) {
            throw EntityRemovedInDatabaseException(oEntityId.getTypeName(), oEntityId, rnf)
        }
    }
}

fun SchemaClass.requireClassId(): Int {
    return getCustom(CLASS_ID_CUSTOM_PROPERTY_NAME)?.toInt()
        ?: throw IllegalStateException("classId not found for ${this.name}")
}

fun Vertex.getTargetLocalEntityIds(linkName: String): LinkBag =
    getProperty<LinkBag>(linkTargetEntityIdPropertyName(linkName))
        ?: LinkBag(boundedToSession as DatabaseSessionEmbedded)

fun Vertex.setTargetLocalEntityIds(linkName: String, ids: LinkBag) {
    setProperty(linkTargetEntityIdPropertyName(linkName), ids)
}

fun Vertex.requireSchemaClass(): SchemaClass =
    schemaClass ?: throw IllegalStateException("schemaClass not found for $this")

fun YTDBVertex.requireSchemaClass(): SchemaClass = raw().requireSchemaClass()

fun YTDBVertex.requireLocalEntityId(): Long =
    property<Long>(LOCAL_ENTITY_ID_PROPERTY_NAME).orElse(null)
        ?: throw IllegalStateException("localEntityId not found for the vertex")

fun YTDBVertex.raw(): Vertex = (this as YTDBVertexInternal).rawEntity

val String.asEdgeClass get() = YTDBVertexEntity.edgeClassName(this)