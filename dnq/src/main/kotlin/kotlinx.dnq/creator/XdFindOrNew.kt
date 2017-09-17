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
package kotlinx.dnq.creator

import jetbrains.exodus.database.EntityCreator
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.query.XdQuery
import kotlinx.dnq.query.filter
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.session

fun <XD : XdEntity> XdEntityType<XD>.findOrNew(findQuery: XdQuery<XD>, initNew: XD.() -> Unit): XD {
    val entityCreator = object : EntityCreator(entityType) {
        override fun find() = findQuery.firstOrNull()?.entity

        override fun created(entity: Entity) {
            wrap(entity).initNew()
        }

    }
    return wrap(storeContainer.store.session.newEntity(entityCreator))
}

fun <XD : XdEntity> XdEntityType<XD>.findOrNew(template: XD.() -> Unit): XD {
    return findOrNew(filter { template(it) }, template)
}
