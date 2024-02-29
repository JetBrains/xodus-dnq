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
package kotlinx.dnq

import com.orientechnologies.orient.core.db.ODatabaseSession
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.query.query
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.store.container.StoreContainer
import kotlinx.dnq.util.getDBName
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

abstract class XdEnumEntityType<XD : XdEnumEntity>(entityTypeName: String? = null, storeContainer: StoreContainer = StaticStoreContainer) :
        XdNaturalEntityType<XD>(entityTypeName, storeContainer) {

    val constants = ArrayList<Const<XD>>()

    fun enumField(dbName: String? = null, init: XD.() -> Unit) = EnumConstPropertyProvider(dbName, init)

    fun initEnumValues(session: ODatabaseSession) {
        if (constants.isNotEmpty()) {
            constants.forEach { enumConst ->
                var xdEnumValue = query(XdEnumEntity::name eq enumConst.enumFieldName).firstOrNull()
                if (xdEnumValue == null) {
                    xdEnumValue = session.newVertex(entityType).toXd()
                    xdEnumValue.vertex.setProperty(XdEnumEntity::name.getDBName(this),enumConst.enumFieldName)
                    enumConst.update(xdEnumValue)
                } else {
                    enumConst.update(xdEnumValue)
                }
            }
        }
    }

    class Const<in XD : XdEnumEntity>(val enumFieldName: String, val update: XD.() -> Unit)

    inner class EnumConstPropertyProvider(val dbName: String?, val init: XD.() -> Unit) {
        operator fun provideDelegate(
            thisRef: XdEntityType<XD>,
            prop: KProperty<*>
        ): ReadOnlyProperty<XdEntityType<XD>, XD> {
            val enumConst = Const(dbName ?: prop.name, init)
            constants.add(enumConst)
            return object : ReadOnlyProperty<XdEntityType<XD>, XD> {
                override fun getValue(thisRef: XdEntityType<XD>, property: KProperty<*>): XD {
                    val entityType = this@XdEnumEntityType
                    val entityTypeName = entityType.entityType
                    val enumFieldName = enumConst.enumFieldName
                    //todo OPTIMIZE ME
                    val value = ODatabaseSession.getActiveSession()
                        .query(
                            "SELECT FROM $entityTypeName WHERE ${XdEnumEntity.ENUM_CONST_NAME_FIELD} = ?",
                            enumFieldName
                        ).stream().findFirst()
                    if (value.isPresent) {
                        return value.get().vertex.get().toXd()
                    } else {
                        throw IllegalStateException("Enum entity $entityTypeName.$enumFieldName is not created")
                    }
                }
            }
        }
    }
}
