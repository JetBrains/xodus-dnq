package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity

val Entity.wrapper: XdEntity get() = XdModel.wrap(this)

fun <T : XdEntity> Entity.wrapper(): T {
    val xdHierarchyNode = XdModel.getOrThrow(this.type)

    val entityConstructor = xdHierarchyNode.entityConstructor
            ?: throw UnsupportedOperationException("Constructor for the type ${this.type} is not found")

    @Suppress("UNCHECKED_CAST")
    return entityConstructor(this) as T
}
