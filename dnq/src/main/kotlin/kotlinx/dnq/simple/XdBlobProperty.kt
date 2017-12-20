/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.simple

import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.util.reattachAndGetBlob
import kotlinx.dnq.util.reattachAndSetBlob
import java.io.InputStream
import kotlin.reflect.KProperty

class XdBlobProperty<in R : XdEntity>(
        dbPropertyName: String?) :
        XdConstrainedProperty<R, InputStream>(
                dbPropertyName,
                emptyList(),
                XdPropertyRequirement.REQUIRED,
                PropertyType.BLOB) {

    override fun getValue(thisRef: R, property: KProperty<*>): InputStream {
        return thisRef.reattachAndGetBlob(property.dbName) ?:
                throw RequiredPropertyUndefinedException(thisRef, property)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: InputStream) {
        thisRef.reattachAndSetBlob(property.dbName, value)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.reattachAndGetBlob(property.dbName) != null
    }
}