/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package kotlinx.dnq.listener

import jetbrains.exodus.database.DNQListener
import jetbrains.exodus.database.IEntityListener
import jetbrains.exodus.database.ListenerInvocation
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.TransientChangesMultiplexer
import jetbrains.exodus.entitystore.listeners.ClassBasedListenersSerialization
import kotlinx.dnq.XdModel
import kotlinx.dnq.util.XdHierarchyNode

open class ClassBasedXdListenersSerialization : ClassBasedListenersSerialization() {

    companion object : ClassBasedXdListenersSerialization()

    override fun getKey(listener: DNQListener<*>): String {
        if (listener is EntityListenerWrapper<*>) {
            return super.getKey(listener.wrapped)
        }
        return super.getKey(listener)
    }

    override fun getListener(invocation: ListenerInvocation, changesMultiplexer: TransientChangesMultiplexer, session: TransientStoreSession): IEntityListener<*>? {
        val typeId = invocation.entityId.typeId
        val entityType = session.store.persistentStore.getEntityType(typeId)
        var typeHierarchy: XdHierarchyNode? = XdModel[entityType]
                ?: throw IllegalStateException("Can't find XdEntityType for $entityType")
        var listener: IEntityListener<*>? = null
        while (typeHierarchy != null && listener == null) {
            listener = changesMultiplexer.findListener(typeHierarchy, invocation.listenerKey)
            typeHierarchy = typeHierarchy.parentNode
        }
        return listener
    }

    private fun TransientChangesMultiplexer.findListener(typeHierarchy: XdHierarchyNode, listenerKey: String): IEntityListener<*>? {
        val currentListeners = typeToListeners[typeHierarchy.entityType.entityType]
        return currentListeners?.firstOrNull { getKey(it) == listenerKey }
    }
}