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
package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.database.TransientStoreSession
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.query.query
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.store.container.StoreContainer
import kotlinx.dnq.util.getDBName
import kotlinx.dnq.util.reattachAndSetPrimitiveValue
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

abstract class XdEnumEntityType<XD : XdEnumEntity>(entityTypeName: String? = null, storeContainer: StoreContainer = StaticStoreContainer) :
        XdNaturalEntityType<XD>(entityTypeName, storeContainer) {

    val constants = ArrayList<Const<XD>>()

    fun enumField(dbName: String? = null, init: XD.() -> Unit) = EnumConstPropertyProvider(dbName, init)

    fun initEnumValues(txn: TransientStoreSession) {
        if (constants.isNotEmpty()) {
            constants.forEach { enumConst ->
                var xdEnumValue = query(XdEnumEntity::name eq enumConst.enumFieldName).firstOrNull()
                if (xdEnumValue == null) {
                    xdEnumValue = wrap(txn.newEntity(entityType))
                    xdEnumValue.reattachAndSetPrimitiveValue(XdEnumEntity::name.getDBName(this), enumConst.enumFieldName, String::class.java)
                    enumConst.update(xdEnumValue)
                } else {
                    enumConst.update(xdEnumValue)
                }

            }
            txn.flush()
        }
    }

    class Const<in XD : XdEnumEntity>(val enumFieldName: String, val update: XD.() -> Unit)

    inner class EnumConstPropertyProvider(val dbName: String?, val init: XD.() -> Unit) {
        operator fun provideDelegate(thisRef: XdEntityType<XD>, prop: KProperty<*>): ReadOnlyProperty<XdEntityType<XD>, XD> {
            val enumConst = Const(dbName ?: prop.name, init)
            constants.add(enumConst)
            return object : ReadOnlyProperty<XdEntityType<XD>, XD> {
                override fun getValue(thisRef: XdEntityType<XD>, property: KProperty<*>): XD {
                    val entityType = this@XdEnumEntityType
                    val transientEntityStore = entityType.entityStore
                    val entityTypeName = entityType.entityType
                    val enumFieldName = enumConst.enumFieldName
                    val result = transientEntityStore.getCachedEnumValue(entityTypeName, enumFieldName) ?: run {
                        val currentPersistentSession = transientEntityStore.persistentStore.currentTransaction
                                ?: throw IllegalStateException("EntityStore: current transaction is not set")

                        val it = currentPersistentSession.find(entityTypeName, XdEnumEntity.ENUM_CONST_NAME_FIELD, enumFieldName).iterator()
                        if (!it.hasNext()) {
                            throw IllegalStateException("Instance not created: $entityTypeName.$enumFieldName")
                        }
                        val result = it.next()
                        if (transientEntityStore is TransientEntityStoreImpl) {
                            transientEntityStore.setCachedEnumValue(entityTypeName, enumFieldName, result)
                        }
                        result
                    }

                    val threadSession = transientEntityStore.threadSession
                            ?: throw IllegalStateException("EntityStore: current transaction is not set")
                    return entityType.wrap(threadSession.newEntity(result))
                }
            }
        }

    }
}
