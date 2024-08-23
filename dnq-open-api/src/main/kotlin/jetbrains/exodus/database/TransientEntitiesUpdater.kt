/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
package jetbrains.exodus.database

import java.io.File
import java.io.InputStream

interface TransientEntitiesUpdater {

    fun addChild(
        parent: TransientEntity,
        parentToChildLinkName: String,
        childToParentLinkName: String,
        child: TransientEntity
    )

    fun setBlob(transientEntity: TransientEntity, blobName: String, stream: InputStream)

    fun setBlob(transientEntity: TransientEntity, blobName: String, file: File)

    fun deleteProperty(transientEntity: TransientEntity, propertyName: String): Boolean

    fun setBlobString(transientEntity: TransientEntity, blobName: String, newValue: String): Boolean
    fun deleteBlob(transientEntity: TransientEntity, blobName: String): Boolean
    fun setLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean
    fun addLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean
    fun deleteLinks(source: TransientEntity, linkName: String)
    fun deleteEntity(transientEntity: TransientEntity): Boolean
    fun deleteLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean
    fun setProperty(transientEntity: TransientEntity, propertyName: String, propertyNewValue: Comparable<*>): Boolean
    fun setToOne(source: TransientEntity, linkName: String, target: TransientEntity?)
    fun setManyToOne(many: TransientEntity, manyToOneLinkName: String, oneToManyLinkName: String, one: TransientEntity?)
    fun clearOneToMany(one: TransientEntity, manyToOneLinkName: String, oneToManyLinkName: String)
    fun createManyToMany(e1: TransientEntity, e1Toe2LinkName: String, e2Toe1LinkName: String, e2: TransientEntity)
    fun clearManyToMany(e1: TransientEntity, e1Toe2LinkName: String, e2Toe1LinkName: String)
    fun setOneToOne(
        e1: TransientEntity,
        e1Toe2LinkName: String,
        e2Toe1LinkName: String,
        e2: TransientEntity?
    )

    fun removeOneToMany(
        one: TransientEntity,
        manyToOneLinkName: String,
        oneToManyLinkName: String,
        many: TransientEntity
    )

    fun removeFromParent(child: TransientEntity, parentToChildLinkName: String, childToParentLinkName: String)
    fun removeChild(
        parent: TransientEntity,
        parentToChildLinkName: String,
        childToParentLinkName: String
    )

    fun setChild(
        parent: TransientEntity,
        parentToChildLinkName: String,
        childToParentLinkName: String,
        child: TransientEntity
    )

    fun clearChildren(parent: TransientEntity, parentToChildLinkName: String)
    fun hasChanges(): Boolean
    fun clear()
    fun apply()
    fun addChange(change: () -> Boolean): () -> Boolean
}
