package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import jetbrains.teamsys.dnq.runtime.files.FileMeta
import jetbrains.teamsys.dnq.runtime.files.PersistentFileImpl
import kotlinx.dnq.simple.containsNone
import java.io.InputStream

class XdFile(override val entity: Entity) : XdEntity() {
    companion object : XdLegacyEntityType<PersistentFileImpl, XdFile>()

    var content by xdRequiredBlobProp()
    var name by xdRequiredStringProp() { containsNone("/\\") }
    var extension by xdStringProp()
    var size by xdLongProp()
    var charset by xdStringProp()
    var mimeType by xdStringProp()
    var trusted by xdBooleanProp()

    var baseName: String
        get() = mpsType.getBaseName(entity)
        set(value) = mpsType.setBaseName(value, entity)

    val stringContent: String
        get() = mpsType.getStringContent(entity)

    val isImage: Boolean
        get() = mpsType.isImage(entity)

    fun getThumbnail(maxWidth: Int, maxHeight: Int, cropped: Boolean, fileMeta: FileMeta?): InputStream {
        return mpsType.getThumbnail(maxWidth, maxHeight, cropped, fileMeta, entity)
    }

    fun resize(maxWidth: Int, maxHeight: Int, crop: Boolean = false) {
        mpsType.resize(maxWidth, maxHeight, crop, entity)
    }
}