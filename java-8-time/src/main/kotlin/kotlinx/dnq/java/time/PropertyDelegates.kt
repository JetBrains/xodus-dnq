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
package kotlinx.dnq.java.time

import jetbrains.exodus.bindings.*
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.PersistentEntityStore
import jetbrains.exodus.util.LightOutputStream
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.simple.*
import kotlinx.dnq.transactional
import kotlinx.dnq.util.XdPropertyCachedProvider
import org.jetbrains.mazine.infer.type.parameter.inferTypeParameterClass
import java.io.ByteArrayInputStream
import java.time.Instant

abstract class PropertyValueSerializer<V : Comparable<V>> : ComparableBinding() {
    val clazz: Class<V> = inferTypeParameterClass(PropertyValueSerializer::class.java, "V", javaClass)

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

fun ByteArrayInputStream.readShort() = BindingUtils.readShort(this)
fun LightOutputStream.writeShort(value: Short) = ShortBinding.BINDING.writeObject(this, value)

fun ByteArrayInputStream.readInt() = BindingUtils.readInt(this)
fun LightOutputStream.writeInt(value: Int) = IntegerBinding.BINDING.writeObject(this, value)

fun ByteArrayInputStream.readLong() = BindingUtils.readLong(this)
fun LightOutputStream.writeLong(value: Long) = LongBinding.BINDING.writeObject(this, value)

fun ByteArrayInputStream.readFloat() = BindingUtils.readFloat(this)
fun LightOutputStream.writeFloat(value: Float) = FloatBinding.BINDING.writeObject(this, value)

fun ByteArrayInputStream.readDouble() = BindingUtils.readDouble(this)
fun LightOutputStream.writeDouble(value: Double) = DoubleBinding.BINDING.writeObject(this, value)

fun ByteArrayInputStream.readString(): String? = BindingUtils.readString(this)


object InstantSerializer : PropertyValueSerializer<Instant>() {
    override fun write(stream: LightOutputStream, value: Instant) {
        val epochSecond = value.epochSecond
        val nano = value.nano
        stream.writeLong(epochSecond)
        stream.writeInt(nano)
    }

    override fun read(stream: ByteArrayInputStream): Instant {
        val epochSecond = stream.readLong()
        val nanoAdjustment = stream.readInt()
        return Instant.ofEpochSecond(epochSecond, nanoAdjustment.toLong())
    }
}

fun <R : XdEntity> XdEntityType<R>.xdInstantProp(dbName: String? = null, constraints: Constraints<R, Instant?>? = null) =
        XdPropertyCachedProvider {
            XdNullableProperty<R, Instant>(
                    Instant::class.java,
                    dbName,
                    constraints.collect()
            )
        }

fun <R : XdEntity> XdEntityType<R>.xdRequiredInstantProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, Instant?>? = null) =
        XdPropertyCachedProvider {
            XdProperty<R, Instant>(
                    Instant::class.java,
                    dbName,
                    constraints.collect(),
                    if (unique) XdPropertyRequirement.UNIQUE else XdPropertyRequirement.REQUIRED,
                    { e, p -> throw RequiredPropertyUndefinedException(e, p) }
            )
        }
