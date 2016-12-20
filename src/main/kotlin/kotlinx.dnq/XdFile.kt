package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import jetbrains.teamsys.dnq.runtime.files.PersistentFileImpl
import kotlinx.dnq.simple.containsNone

class XdFile(override val entity: Entity) : XdEntity() {
    companion object : XdLegacyEntityType<PersistentFileImpl, XdFile>()

    var content by xdRequiredBlobProp()
    var name by xdRequiredStringProp() { containsNone("/\\") }
    var extension by xdStringProp()
    var size by xdLongProp()
    var charset by xdStringProp()
    var mimeType by xdStringProp()
    var trusted by xdBooleanProp()
}