package com.jetbrains.teamsys.dnq.database

import com.orientechnologies.orient.core.db.ODatabaseSession
import com.orientechnologies.orient.core.record.OVertex
import com.orientechnologies.orient.core.record.impl.ORecordBytes
import jetbrains.exodus.database.LinkChangeType
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityOriginalValuesProvider
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.orientdb.OComparableSet
import jetbrains.exodus.util.UTFUtil
import java.io.ByteArrayInputStream
import java.io.InputStream

class TransientEntityOriginalValuesProviderImpl(private val session: TransientStoreSession) : TransientEntityOriginalValuesProvider {
    private val transientChangesTracker get() = session.transientChangesTracker

    override fun getOriginalPropertyValue(e: TransientEntity, propertyName: String): Comparable<*>? {
        val session = ODatabaseSession.getActiveSession()
        val id = e.entity.id.asOId()
        val oVertex = session.load<OVertex>(id)
        val onLoadValue = oVertex.getPropertyOnLoadValue<Any>(propertyName)
        return if (onLoadValue is MutableSet<*>) {
            OComparableSet(onLoadValue)
        } else {
            onLoadValue as Comparable<*>?
        }
    }

    override fun getOriginalBlobStringValue(e: TransientEntity, blobName: String): String? {
        val session = ODatabaseSession.getActiveSession()
        val id = e.entity.id.asOId()
        val oVertex = session.load<OVertex>(id)
        val blobHolder = oVertex.getPropertyOnLoadValue<ORecordBytes?>(blobName)
        return blobHolder?.toStream()?.let {
            UTFUtil.readUTF((it).inputStream())
        }
    }

    override fun getOriginalBlobValue(e: TransientEntity, blobName: String): InputStream? {
        val session = ODatabaseSession.getActiveSession()
        val id = e.entity.id.asOId()
        val oVertex = session.load<OVertex>(id)
        val blobHolder = oVertex.getPropertyOnLoadValue<ORecordBytes?>(blobName)
        return blobHolder?.let {
            ByteArrayInputStream(blobHolder.toStream())
        }
    }

    override fun getOriginalLinkValue(e: TransientEntity, linkName: String): Entity? {
        // get from saved changes, if not - from db
        val change = transientChangesTracker.getChangedLinksDetailed(e)?.get(linkName)
        if (change != null) {
            when (change.changeType) {
                LinkChangeType.ADD_AND_REMOVE,
                LinkChangeType.REMOVE -> {
                    return if (change.removedEntitiesSize != 1) {
                        if (change.deletedEntitiesSize == 1) {
                            change.deletedEntities!!.iterator().next()
                        } else {
                            throw IllegalStateException("Can't determine original link value: ${e.type}.$linkName")
                        }
                    } else {
                        change.removedEntities!!.iterator().next()
                    }
                }

                else ->
                    throw IllegalStateException("Incorrect change type for link that is part of index: ${e.type}.$linkName: ${change.changeType.getName()}")
            }
        }
        return e.entity.getLink(linkName)
    }

}
