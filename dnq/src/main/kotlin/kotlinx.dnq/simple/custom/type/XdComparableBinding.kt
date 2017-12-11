package kotlinx.dnq.simple.custom.type

import jetbrains.exodus.bindings.ComparableBinding
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.PersistentEntityStore
import jetbrains.exodus.util.LightOutputStream
import kotlinx.dnq.transactional
import org.jetbrains.mazine.infer.type.parameter.inferTypeParameterClass
import java.io.ByteArrayInputStream

abstract class XdComparableBinding<V : Comparable<V>> : ComparableBinding() {
    val clazz: Class<V> = inferTypeParameterClass(XdComparableBinding::class.java, "V", javaClass)

    fun register(store: TransientEntityStore) {
        store.transactional { txn ->
            val persistentStore = store.persistentStore as PersistentEntityStore
            persistentStore.registerCustomPropertyType(txn.persistentTransaction, clazz, this)
        }
    }

    @Suppress("UNCHECKED_CAST")
    final override fun writeObject(output: LightOutputStream, value: Comparable<V>) = write(output, value as V)

    final override fun readObject(stream: ByteArrayInputStream) = read(stream)

    abstract fun write(stream: LightOutputStream, value: V)

    abstract fun read(stream: ByteArrayInputStream): V
}
