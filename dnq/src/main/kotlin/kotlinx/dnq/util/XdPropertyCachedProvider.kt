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
package kotlinx.dnq.util

import jetbrains.exodus.core.dataStructures.ConcurrentIntObjectCache
import kotlinx.dnq.XdEntity
import kotlin.reflect.KProperty

class XdPropertyCachedProvider<out D>(private val create: () -> D) {

    companion object {

        private val cacheSize = System.getProperty("kotlinx.dnq.delegateProvider.cacheSize", "2000").toInt()

        internal val cache = ConcurrentIntObjectCache<KPropertyHolder<*>>(cacheSize, 2)

        internal class KPropertyHolder<D>(val prop: KProperty<*>, val delegate: D)
    }

    @Suppress("UNCHECKED_CAST")
    operator fun provideDelegate(thisRef: XdEntity?, prop: KProperty<*>): D {
        val key = System.identityHashCode(prop)
        cache.tryKey(key)?.let { ph ->
            if (ph.prop === prop) {
                return ph.delegate as D
            }
        }
        return create().also { delegate ->
            cache.cacheObject(key, KPropertyHolder(prop, delegate))
        }
    }
}
