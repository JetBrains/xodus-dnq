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
import jetbrains.exodus.entitystore.listeners.ClassBasedListenersSerialization

class ClassBasedXdListenersSerialization : ClassBasedListenersSerialization() {

    override fun getKey(listener: DNQListener<*>): String {
        if (listener is EntityListenerWrapper<*>) {
            return super.getKey(listener.wrapped)
        }
        return super.getKey(listener)
    }
}