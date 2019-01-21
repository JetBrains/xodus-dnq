/**
 * Copyright 2006 - 2019 JetBrains s.r.o.
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
package kotlinx.dnq

import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.store.container.StoreContainer
import kotlin.reflect.KProperty1

abstract class XdNaturalEntityType<T : XdEntity>(
        entityType: String? = null,
        storeContainer: StoreContainer = StaticStoreContainer
) : XdEntityType<T>(storeContainer) {

    open val compositeIndices = emptyList<List<KProperty1<T, *>>>()

    override val entityType = entityType ?:
            javaClass.enclosingClass?.simpleName ?:
            throw IllegalArgumentException("Cannot infer entity type for ${javaClass.canonicalName}")

    open fun initEntityType() {
        // Do nothing by default
    }
}
