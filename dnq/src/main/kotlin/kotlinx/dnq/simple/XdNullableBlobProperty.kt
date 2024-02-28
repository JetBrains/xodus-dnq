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
import kotlinx.dnq.XdEntity
import kotlinx.dnq.orientdb.DnqSchemaToOrientDB
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.reflect.KProperty

class XdNullableBlobProperty<in R : XdEntity>(dbPropertyName: String?) :
        XdMutableConstrainedProperty<R, InputStream?>(
                dbPropertyName,
                emptyList(),
                XdPropertyRequirement.OPTIONAL,
                PropertyType.BLOB) {

    override fun getValue(thisRef: R, property: KProperty<*>): InputStream? {
        val vertex = thisRef.reload()
        val element = vertex.getLinkProperty(property.dbName)
        return (element as? OElement)?.let {
            ByteArrayInputStream(element.getProperty<ByteArray>(DnqSchemaToOrientDB.DATA_PROPERTY_NAME))
        }
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: InputStream?) {
        val vertex = thisRef.vertex
        val element = vertex.getLinkProperty(property.dbName) as? OElement
        if (value != null){
            val notNullElement = element ?: run {
                val session = ODatabaseSession.getActiveSession()
                session.newElement(DnqSchemaToOrientDB.BINARY_BLOB_CLASS_NAME)
            }
            notNullElement.setProperty(DnqSchemaToOrientDB.DATA_PROPERTY_NAME, value.readAllBytes())
        } else {
            vertex.removeProperty<OElement>(property.dbName)
            element?.delete()
        }
        //todo should we save element also here
        vertex.save<OVertex>()
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.vertex.hasProperty(property.dbName)
    }

}
