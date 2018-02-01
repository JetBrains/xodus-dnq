package kotlinx.dnq.sequence

import com.jetbrains.teamsys.dnq.database.threadSessionOrThrow
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Sequence
import kotlinx.dnq.XdEntity
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class XdSequenceProperty<in R : XdEntity>(val dbPropertyName: String?) : ReadOnlyProperty<R, Sequence> {
    override fun getValue(thisRef: R, property: KProperty<*>): Sequence {
        val transientEntity = thisRef.entity as TransientEntity
        val session = transientEntity.store.threadSessionOrThrow
        return session.getSequence("${transientEntity.id}${dbPropertyName ?: property.name}")
    }
}