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

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException

import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.CLASS_ID_CUSTOM_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.CLASS_ID_SEQUENCE_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.LOCAL_ENTITY_ID_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.localEntityIdSequenceName
import java.util.concurrent.ConcurrentHashMap

interface YTDBSchemaBuddy {
    fun initialize(session: DatabaseSessionEmbedded)

    fun resolveEntityIdOrNull(session: DatabaseSessionEmbedded, typeId: Int, localId: Long): RIDEntityId?

    /**
     * If the class has not been found, returns -1. It is how it was in the Classic Xodus.
     */
    fun getTypeId(session: DatabaseSessionEmbedded, entityType: String): Int

    fun getType(session: DatabaseSessionEmbedded, entityTypeId: Int): String

    fun requireTypeExists(session: DatabaseSessionEmbedded, entityType: String)

    fun getOrCreateSequence(
        session: DatabaseSessionEmbedded,
        sequenceName: String,
        initialValue: Long
    ): DBSequence

    fun getSequence(session: DatabaseSessionEmbedded, sequenceName: String): DBSequence

    fun getSequenceOrNull(session: DatabaseSessionEmbedded, sequenceName: String): DBSequence?

    fun updateSequence(session: DatabaseSessionEmbedded, sequenceName: String, currentValue: Long)

    fun renameOClass(session: DatabaseSessionEmbedded, oldName: String, newName: String)

    fun deleteOClass(session: DatabaseSessionEmbedded, name: String)

    fun getOrCreateEdgeClass(
        session: DatabaseSessionEmbedded,
        linkName: String,
        outClassName: String,
        inClassName: String
    ): SchemaClass
}

class YTDBSchemaBuddyImpl(
    private val dbProvider: YTDBDatabaseProvider,
    autoInitialize: Boolean = true,
) : YTDBSchemaBuddy {
    companion object {
        val INTERNAL_CLASS_NAMES = hashSetOf(SchemaClass.VERTEX_CLASS_NAME)
    }

    private val classIdToOClassId = ConcurrentHashMap<Int, Pair<Int, String>>()

    init {
        if (autoInitialize) {
            dbProvider.withSession(this::initialize)
        }
    }

    override fun initialize(session: DatabaseSessionEmbedded) {
        session.createClassIdSequenceIfAbsent()
        for (oClass in session.schema.classes) {
            if (oClass.isVertexType && !INTERNAL_CLASS_NAMES.contains(oClass.name)) {
                classIdToOClassId[oClass.requireClassId()] = oClass.collectionIds[0] to oClass.name
            }
        }
    }

    override fun getOrCreateSequence(
        session: DatabaseSessionEmbedded,
        sequenceName: String,
        initialValue: Long
    ): DBSequence {
        val oSequence =
            (session as DatabaseSessionEmbedded).metadata.sequenceLibrary.getSequence(sequenceName)
        if (oSequence != null) return oSequence


        return dbProvider.withSession { dbSession ->
            val params = DBSequence.CreateParams().setStart(initialValue).setIncrement(1)
            (dbSession as DatabaseSessionEmbedded).metadata.sequenceLibrary.createSequence(
                sequenceName,
                DBSequence.SEQUENCE_TYPE.ORDERED,
                params
            )
        }
    }

    override fun renameOClass(session: DatabaseSessionEmbedded, oldName: String, newName: String) {
        dbProvider.withSession { sessionToWork ->
            val oldClass = sessionToWork.schema.getClass(oldName)
                ?: throw IllegalArgumentException("Class $oldName not found")
            oldClass.setName(newName)
        }
    }

    override fun deleteOClass(session: DatabaseSessionEmbedded, name: String) {
        dbProvider.withSession { sessionToWork ->
            val targetClass = sessionToWork.schema.getClass(name)
            if (targetClass != null) {
                sessionToWork.schema.dropClass(name)
            }
        }
    }

    override fun getOrCreateEdgeClass(
        session: DatabaseSessionEmbedded,
        linkName: String,
        outClassName: String,
        inClassName: String
    ): SchemaClass {
        val edgeClassName = YTDBVertexEntity.edgeClassName(linkName)
        val oClass = session.schema.getClass(edgeClassName)
        if (oClass != null) return oClass

        dbProvider.withSession { it.schema.createEdgeClass(edgeClassName) }

        return session.schema.getClass(edgeClassName)
            ?: throw IllegalStateException("Class $edgeClassName could not be created")
    }

    override fun getSequence(session: DatabaseSessionEmbedded, sequenceName: String): DBSequence {
        return (session as DatabaseSessionEmbedded).metadata.sequenceLibrary.getSequence(
            sequenceName
        )
            ?: throw IllegalStateException("$sequenceName sequence not found")
    }

    override fun getSequenceOrNull(session: DatabaseSessionEmbedded, sequenceName: String): DBSequence? {
        return (session as DatabaseSessionEmbedded).metadata.sequenceLibrary.getSequence(
            sequenceName
        )
    }

    override fun updateSequence(
        session: DatabaseSessionEmbedded,
        sequenceName: String,
        currentValue: Long
    ) {
        dbProvider.withSession { sessionToWork ->
            sessionToWork.begin();
            getSequence(sessionToWork, sequenceName).updateParams(
                sessionToWork as DatabaseSessionEmbedded,
                DBSequence.CreateParams().setCurrentValue(
                    currentValue
                )
            )
            sessionToWork.commit()
        }
    }

    override fun resolveEntityIdOrNull(
        session: DatabaseSessionEmbedded,
        typeId: Int,
        localId: Long
    ): RIDEntityId? {
        // Keep in mind that it is possible that we are given an entityId that is not in the database.
        // It is a valid case: we return null to signal "not found".

        val classId = typeId
        val localEntityId = localId
        val oClassId = classIdToOClassId[classId]?.first ?: return null
        val schema = session.schema
        val oClass = schema.getClassByCollectionId(oClassId) ?: return null

        val oid = session.activeTransaction
            .query("SELECT FROM ${oClass.name} WHERE $LOCAL_ENTITY_ID_PROPERTY_NAME = ?", localEntityId)
            .use { resultSet ->
                if (resultSet.hasNext()) {
                    resultSet.next().asVertexOrNull()?.identity ?: return null
                } else {
                    return null
                }
            }

        return RIDEntityId(classId, localEntityId, oid, oClass.name)
    }

    override fun getTypeId(session: DatabaseSessionEmbedded, entityType: String): Int {
        return session.schema.getClass(entityType)?.requireClassId() ?: -1
    }

    override fun getType(
        session: DatabaseSessionEmbedded,
        entityTypeId: Int
    ): String {
        val (_, typeName) = classIdToOClassId.computeIfAbsent(entityTypeId) {
            val oClass = session.schema.classes.find { oClass ->
                oClass.getCustom(CLASS_ID_CUSTOM_PROPERTY_NAME)?.toInt() == entityTypeId
            } ?: throw EntityRemovedInDatabaseException("Invalid type ID $entityTypeId")
            oClass.requireClassId() to oClass.name
        }
        return typeName
    }

    override fun requireTypeExists(session: DatabaseSessionEmbedded, entityType: String) {
        val oClass = session.schema.getClass(entityType)
        check(oClass != null) { "$entityType has not been found" }
    }

}

fun DatabaseSessionEmbedded.createClassIdSequenceIfAbsent(startFrom: Long = -1L) {
    createSequenceIfAbsent(CLASS_ID_SEQUENCE_NAME, startFrom)
}

fun DatabaseSessionEmbedded.createLocalEntityIdSequenceIfAbsent(
    oClass: SchemaClass,
    startFrom: Long = -1L
) {
    createSequenceIfAbsent(localEntityIdSequenceName(oClass.name), startFrom)
}

private fun DatabaseSessionEmbedded.createSequenceIfAbsent(sequenceName: String, startFrom: Long = 0L) {
    val sequences = (this as DatabaseSessionEmbedded).metadata.sequenceLibrary
    if (sequences.getSequence(sequenceName) == null) {
        val params = DBSequence.CreateParams()
        params.start = startFrom
        sequences.createSequence(sequenceName, DBSequence.SEQUENCE_TYPE.ORDERED, params)
    }
}

fun DatabaseSessionEmbedded.setClassIdIfAbsent(oClass: SchemaClass) {
    if (oClass.getCustom(CLASS_ID_CUSTOM_PROPERTY_NAME) == null) {
        val sequences = (this as DatabaseSessionEmbedded).metadata.sequenceLibrary
        val sequence: DBSequence = sequences.getSequence(CLASS_ID_SEQUENCE_NAME)
            ?: throw IllegalStateException("$CLASS_ID_SEQUENCE_NAME not found")

        oClass.setCustom(CLASS_ID_CUSTOM_PROPERTY_NAME, sequence.next(this).toString())
    }
}

fun setLocalEntityId(tx: YTDBStoreTransaction, className: String, vertex: YTDBVertex) {
    val sequenceName = localEntityIdSequenceName(className)
    val id = tx.getSequenceNextValue(sequenceName)
    vertex.property(LOCAL_ENTITY_ID_PROPERTY_NAME, id)
}

fun DatabaseSessionEmbedded.createVertexClassWithClassId(className: String): SchemaClass {
    createClassIdSequenceIfAbsent()
    val oClass = schema.createVertexClass(className)
    setClassIdIfAbsent(oClass)
    createLocalEntityIdSequenceIfAbsent(oClass)
    return oClass
}

internal fun DatabaseSessionEmbedded.getOrCreateVertexClass(className: String): SchemaClass {
    val existingClass = this.schema.getClass(className)
    if (existingClass != null) return existingClass

    return createVertexClassWithClassId(className)
}
