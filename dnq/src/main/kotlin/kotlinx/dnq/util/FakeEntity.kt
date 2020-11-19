/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
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

import jetbrains.exodus.ByteIterable
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityStore
import java.io.File
import java.io.InputStream

/**
 * This class is required to create fake entity that is used to collect metadata
 */
internal object FakeEntity : Entity by UnsupportedOperationEntity

internal object UnsupportedOperationEntity : Entity {
    override fun getRawProperty(propertyName: String): ByteIterable? = throw unsupportedOperationException()
    override fun getLinks(linkName: String): EntityIterable = throw unsupportedOperationException()
    override fun getLinks(linkNames: MutableCollection<String>): EntityIterable = throw unsupportedOperationException()
    override fun setBlob(blobName: String, blob: InputStream) = throw unsupportedOperationException()
    override fun setBlob(blobName: String, file: File) = throw unsupportedOperationException()
    override fun setBlobString(blobName: String, blobString: String): Boolean = throw unsupportedOperationException()
    override fun getId(): EntityId = throw unsupportedOperationException()
    override fun deleteLink(linkName: String, entity: Entity): Boolean = throw unsupportedOperationException()
    override fun deleteLink(linkName: String, targetId: EntityId): Boolean = throw unsupportedOperationException()
    override fun setLink(linkName: String, target: Entity?): Boolean = throw unsupportedOperationException()
    override fun setLink(linkName: String, targetId: EntityId): Boolean = throw unsupportedOperationException()
    override fun getProperty(propertyName: String): Comparable<Nothing> = throw unsupportedOperationException()
    override fun getBlobNames(): MutableList<String> = throw unsupportedOperationException()
    override fun getLink(linkName: String): Entity? = throw unsupportedOperationException()
    override fun deleteLinks(linkName: String) = throw unsupportedOperationException()
    override fun getPropertyNames(): MutableList<String> = throw unsupportedOperationException()
    override fun getStore(): EntityStore = throw unsupportedOperationException()
    override fun deleteBlob(blobName: String): Boolean = throw unsupportedOperationException()
    override fun delete(): Boolean = throw unsupportedOperationException()
    override fun addLink(linkName: String, target: Entity): Boolean = throw unsupportedOperationException()
    override fun addLink(linkName: String, targetId: EntityId): Boolean = throw unsupportedOperationException()
    override fun setProperty(propertyName: String, value: Comparable<Nothing>): Boolean = throw unsupportedOperationException()
    override fun getLinkNames(): MutableList<String> = throw unsupportedOperationException()
    override fun getType(): String = throw unsupportedOperationException()
    override fun compareTo(other: Entity?): Int = throw unsupportedOperationException()
    override fun deleteProperty(propertyName: String): Boolean = throw unsupportedOperationException()
    override fun getBlobString(blobName: String): String? = throw unsupportedOperationException()
    override fun toIdString(): String = throw unsupportedOperationException()
    override fun getBlob(blobName: String): InputStream? = throw unsupportedOperationException()
    override fun getBlobSize(blobName: String): Long = throw unsupportedOperationException()

    private fun unsupportedOperationException() = UnsupportedOperationException("not implemented")
}