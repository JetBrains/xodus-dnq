package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity

val Entity.wrapper: XdEntity get() = XdModel.wrap(this)

@Suppress("UNCHECKED_CAST")
@Deprecated("Use wrap(entityType)", replaceWith = ReplaceWith("this.wrap(T)"))
fun <T : XdEntity> Entity.wrapper(): T = this.wrapper as T

fun <T : XdEntity> Entity.wrap(entityType: XdEntityType<T>): T = entityType.wrap(this)
