/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package jetbrains.exodus.entitystore.listeners

import jetbrains.exodus.database.DNQListener
import jetbrains.exodus.database.IEntityListener
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.TransientChangesMultiplexer

interface TransientListenersSerialization {

    fun getKey(listener: DNQListener<*>): String

    fun getListener(invocation: ListenerInvocation,
                    changesMultiplexer: TransientChangesMultiplexer,
                    session: TransientStoreSession): IEntityListener<*>?
}


open class ClassBasedListenersSerialization : TransientListenersSerialization {

    override fun getKey(listener: DNQListener<*>): String {
        return listener.javaClass.name
    }

    override fun getListener(invocation: ListenerInvocation,
                             changesMultiplexer: TransientChangesMultiplexer,
                             session: TransientStoreSession): IEntityListener<*>? {
        val typeId = invocation.entityId.typeId
        val currentListeners = changesMultiplexer.typeToListeners[
                session.store.persistentStore.getEntityType(typeId)]
        return currentListeners?.first { getKey(it) == invocation.listenerKey }
    }
}