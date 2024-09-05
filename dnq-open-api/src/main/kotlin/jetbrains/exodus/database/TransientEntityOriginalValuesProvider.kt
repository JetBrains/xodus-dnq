package jetbrains.exodus.database

import jetbrains.exodus.entitystore.Entity
import java.io.InputStream

interface TransientEntityOriginalValuesProvider {
    fun getOriginalPropertyValue(e: TransientEntity, propertyName: String): Comparable<*>?
    fun getOriginalBlobStringValue(e: TransientEntity, blobName: String): String?
    fun getOriginalBlobValue(e: TransientEntity, blobName: String): InputStream?
    fun getOriginalLinkValue(e: TransientEntity, linkName: String): Entity?
}
