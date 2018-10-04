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
