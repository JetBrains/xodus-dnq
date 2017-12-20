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
package kotlinx.dnq.simple.custom.type

import java.util.concurrent.ConcurrentHashMap

object XdCustomTypeBindingRegistry {
    private val bindings = ConcurrentHashMap<Class<*>, XdCustomTypeBinding<*>>()

    operator fun <V : Comparable<V>> set(clazz: Class<V>, binding: XdCustomTypeBinding<V>) {
        bindings[clazz] = binding
    }

    operator fun <V : Comparable<V>> get(clazz: Class<V>): XdCustomTypeBinding<V>? {
        @Suppress("UNCHECKED_CAST")
        return bindings[clazz] as XdCustomTypeBinding<V>?
    }
}