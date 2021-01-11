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
package jetbrains.exodus.database

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntity

interface TransientEntity : Entity {

    val isNew: Boolean

    val isSaved: Boolean

    val isRemoved: Boolean

    val isReadonly: Boolean

    val isWrapper: Boolean

    /**
     * @return underlying persistent entity
     */
    val persistentEntity: PersistentEntity

    /**
     * Gets incoming links to entity.
     *
     * @return list of pairs of link name and an entities which is linked with the entity being deleted.
     */
    val incomingLinks: List<Pair<String, EntityIterable>>

    val debugPresentation: String

    val parent: Entity?

    override fun getStore(): TransientEntityStore

    fun getLinksSize(linkName: String): Long

    fun hasChanges(): Boolean

    fun hasChanges(property: String): Boolean

    fun hasChangesExcepting(properties: Array<String>): Boolean

    fun getAddedLinks(name: String): EntityIterable

    fun getRemovedLinks(name: String): EntityIterable

    fun getAddedLinks(linkNames: Set<String>): EntityIterable

    fun getRemovedLinks(linkNames: Set<String>): EntityIterable

    fun getPropertyOldValue(propertyName: String): Comparable<*>?

    fun setToOne(linkName: String, target: Entity?)

    fun setManyToOne(manyToOneLinkName: String, oneToManyLinkName: String, one: Entity?)

    fun clearOneToMany(manyToOneLinkName: String, oneToManyLinkName: String)

    fun createManyToMany(e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity)

    fun clearManyToMany(e1Toe2LinkName: String, e2Toe1LinkName: String)

    fun setOneToOne(e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity?)

    fun removeOneToMany(manyToOneLinkName: String, oneToManyLinkName: String, many: Entity)

    fun removeFromParent(parentToChildLinkName: String, childToParentLinkName: String)

    fun removeChild(parentToChildLinkName: String, childToParentLinkName: String)

    fun setChild(parentToChildLinkName: String, childToParentLinkName: String, child: Entity)

    fun clearChildren(parentToChildLinkName: String)

    fun addChild(parentToChildLinkName: String, childToParentLinkName: String, child: Entity)
}
