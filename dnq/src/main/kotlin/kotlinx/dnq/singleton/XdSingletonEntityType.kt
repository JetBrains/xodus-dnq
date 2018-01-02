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
package kotlinx.dnq.singleton

import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.store.container.StoreContainer


abstract class XdSingletonEntityType<XD : XdEntity>(entityTypeName: String? = null, storeContainer: StoreContainer = StaticStoreContainer) :
        XdNaturalEntityType<XD>(entityTypeName, storeContainer) {

    fun get() = all().firstOrNull() ?: new {
        initSingleton()
    }

    protected abstract fun XD.initSingleton()

}
