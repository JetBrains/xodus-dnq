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
package kotlinx.dnq.listener

import jetbrains.exodus.database.DNQListener
import jetbrains.exodus.entitystore.listeners.AsyncListenersReplication
import jetbrains.exodus.entitystore.listeners.ListenerInvocationTransport
import jetbrains.exodus.entitystore.listeners.ListenerMataData
import jetbrains.exodus.entitystore.listeners.TransientListenersSerialization
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.javaMethod

open class AsyncXdListenersReplication(listenersSerialization: TransientListenersSerialization,
                                       transport: ListenerInvocationTransport) : AsyncListenersReplication(listenersSerialization, transport) {

    override val DNQListener<*>.metadataKey: String
        get() {
            if (this is EntityListenerWrapper<*>) {
                return listenersSerialization.getKey(wrapped)
            }
            return listenersSerialization.getKey(this)
        }

    override val DNQListener<*>.metadata: ListenerMataData
        get() {
            if (this is EntityListenerWrapper<*>) {
                return ListenerMataData(
                        hasAsyncAdded = hasOverride(wrapped, XdEntityListener<*>::addedAsync.javaMethod!!),
                        hasAsyncUpdated = hasOverride(wrapped, XdEntityListener<*>::updatedAsync.javaMethod!!),
                        hasAsyncRemoved = hasOverride(wrapped, XdEntityListener<*>::removedAsync.javaMethod!!)
                )
            }
            return ListenerMataData(
                    hasAsyncAdded = hasOverride(this, DNQListener<*>::addedAsync.javaMethod!!),
                    hasAsyncUpdated = hasOverride(this, DNQListener<*>::updatedAsync.javaMethod!!),
                    hasAsyncRemoved = hasOverride(this, DNQListener<*>::removedAsync.javaMethod!!)
            )
        }

    private fun hasOverride(listener: DNQListener<*>, method: Method): Boolean {
        val kclass: KClass<DNQListener<*>> = listener.javaClass.kotlin
        return kclass.memberFunctions.first { it.name == method.name } in kclass.declaredFunctions
    }

}

