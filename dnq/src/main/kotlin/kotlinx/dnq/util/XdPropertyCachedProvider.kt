/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package kotlinx.dnq.util

import jetbrains.exodus.core.dataStructures.ConcurrentObjectCache
import kotlinx.dnq.XdEntity
import kotlin.reflect.KProperty

class XdPropertyCachedProvider<out D>(private val create: () -> D) {

    companion object {

        private val cache = ConcurrentObjectCache<KPropertyHolder, Any>(2000)

        private class KPropertyHolder(val prop: KProperty<*>) {

            override fun hashCode(): Int {
                return System.identityHashCode(prop)
            }

            override fun equals(other: Any?): Boolean {
                return other is KPropertyHolder && prop === other.prop
            }
        }
    }

    operator fun provideDelegate(thisRef: XdEntity?, prop: KProperty<*>): D {
        val key = KPropertyHolder(prop)
        val instance = cache.getObject(key) ?: return (create()).also {
            cache.cacheObject(key, it!!)
        }

        @Suppress("UNCHECKED_CAST")
        return instance as D
    }
}
