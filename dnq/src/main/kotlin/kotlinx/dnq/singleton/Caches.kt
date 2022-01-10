/**
 * Copyright 2006 - 2022 JetBrains s.r.o.
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
package kotlinx.dnq.singleton

import jetbrains.exodus.core.dataStructures.SoftConcurrentLongObjectCache
import jetbrains.exodus.database.TransientEntityStore
import kotlinx.dnq.XdEntity

val <XD : XdEntity> XdSingletonEntityType<XD>.cacheKey get() = (entityStore.hashCode().toLong() shl 32) + hashCode().toLong()

object SingletonEntitiesNoCacheImpl : XdSingletonEntitiesCache {

    override fun <XD : XdEntity> getOrPut(type: XdSingletonEntityType<XD>, findOrNew: () -> XD): XD {
        return findOrNew()
    }
}

open class SingletonEntitiesCacheImpl(size: Int) : XdSingletonEntitiesCache {

    protected open val cache = SoftConcurrentLongObjectCache<CachedValue>(size)

    override fun <XD : XdEntity> getOrPut(type: XdSingletonEntityType<XD>, findOrNew: () -> XD): XD {
        val cacheKey = type.cacheKey
        val cached = cache.tryKey(cacheKey)

        // with very small probability we can have a collision here
        if (cached != null && cached.store === type.storeContainer.store) {
            return cached.xdEntity as XD
        }
        val instance = findOrNew()
        cache.cacheObject(cacheKey, CachedValue(type.storeContainer.store, instance))
        return instance
    }

}

open class CachedValue(val store: TransientEntityStore, val xdEntity: XdEntity)

