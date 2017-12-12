/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
