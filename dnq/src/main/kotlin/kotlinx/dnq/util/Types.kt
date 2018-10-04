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

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.query.metadata.ModelMetaData
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType

fun XdEntity.isInstanceOf(type: XdEntityType<*>): Boolean {
    return isInstanceOf(type.entityType)
}

fun XdEntity.isInstanceOf(type: String): Boolean {
    return isTypeOf((entity as TransientEntity).store.modelMetaData!!, entity.type, type)
}

fun isTypeOf(mmd: ModelMetaData, type: String, ofType: String): Boolean {
    var currentType: String? = type
    do {
        if (currentType == null) {
            break
        }
        if (currentType == ofType) {
            return true
        }
        val emd = mmd.getEntityMetaData(currentType) ?: break
        for (iFace in emd.interfaceTypes) {
            if (iFace == ofType) {
                return true
            }
        }
        currentType = emd.superType
    } while (true)
    return false
}
