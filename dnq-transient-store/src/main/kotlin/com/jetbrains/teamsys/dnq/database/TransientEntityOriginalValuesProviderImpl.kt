/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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

import com.orientechnologies.orient.core.db.ODatabaseSession
import com.orientechnologies.orient.core.db.record.OIdentifiable
import com.orientechnologies.orient.core.id.ORID
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
        val blobHolderOrId = oVertex.getPropertyOnLoadValue<OIdentifiable?>(blobName)
        var blobHolder: ORecordBytes? = null
        if (blobHolderOrId !is ORecordBytes && blobHolderOrId is ORID) {
            blobHolder = session.load(blobHolderOrId)
        } else if (blobHolderOrId is ORecordBytes) {
            blobHolder = blobHolderOrId
        }
        return blobHolder?.toStream()?.let {
            UTFUtil.readUTF((it).inputStream())
        }
    }

    override fun getOriginalBlobValue(e: TransientEntity, blobName: String): InputStream? {
        val session = ODatabaseSession.getActiveSession()
        val id = e.entity.id.asOId()
        val oVertex = session.load<OVertex>(id)
        var blobHolder: ORecordBytes? = null
        val blobHolderOrId = oVertex.getPropertyOnLoadValue<OIdentifiable?>(blobName)
        if (blobHolderOrId !is ORecordBytes && blobHolderOrId is ORID) {
            blobHolder = session.load(blobHolderOrId)
        } else if (blobHolderOrId is ORecordBytes) {
            blobHolder = blobHolderOrId as ORecordBytes
        }
        return blobHolder?.toStream()?.let {
            ByteArrayInputStream(it)
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
