package kotlinx.dnq

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdModel.toXd

val Entity.wrapper: XdEntity get() = toXd(this)

@Deprecated("Use toXd() instead. May be removed after 01.09.2017", ReplaceWith("toXd<T>()"))
fun <T : XdEntity> Entity.wrapper() = XdModel.toXd<T>(this)

fun <T : XdEntity> Entity.toXd() = XdModel.toXd<T>(this)

val TransientEntityStore.session: TransientStoreSession get() = threadSession ?: throw IllegalStateException("No current transient session.")
