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
package kotlinx.dnq.simple

import com.orientechnologies.orient.core.db.ODatabaseSession
import com.orientechnologies.orient.core.record.OElement
import com.orientechnologies.orient.core.record.OVertex
import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.orientdb.DnqSchemaToOrientDB.Companion.BINARY_BLOB_CLASS_NAME
import kotlinx.dnq.orientdb.DnqSchemaToOrientDB.Companion.DATA_PROPERTY_NAME
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.reflect.KProperty

class XdBlobProperty<in R : XdEntity>(
    dbPropertyName: String?
) :
    XdMutableConstrainedProperty<R, InputStream>(
        dbPropertyName,
        emptyList(),
        XdPropertyRequirement.REQUIRED,
        PropertyType.BLOB
    ) {

    override fun getValue(thisRef: R, property: KProperty<*>): InputStream {
        val vertex = thisRef.reload()
        val element = vertex.getLinkProperty(property.dbName) ?: RequiredPropertyUndefinedException(thisRef, property)
        element as OElement
        return ByteArrayInputStream(element.getProperty(DATA_PROPERTY_NAME))
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: InputStream) {
        val vertex = thisRef.vertex
        val element = vertex.getLinkProperty(property.dbName) as? OElement ?: run {
            val session = ODatabaseSession.getActiveSession()
            session.newElement(BINARY_BLOB_CLASS_NAME)
        }
        element.setProperty(DATA_PROPERTY_NAME, value.readAllBytes())
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.vertex.hasProperty(property.dbName)
    }
}
