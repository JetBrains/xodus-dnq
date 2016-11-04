package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity

val Entity.wrapper: XdEntity get() = XdModel.wrap(this)

@Suppress("UNCHECKED_CAST")
fun <T : XdEntity> Entity.wrapper(): T = this.wrapper as T