package kotlinx.dnq.simple.custom.type

import jetbrains.exodus.bindings.*
import jetbrains.exodus.util.LightOutputStream
import java.io.ByteArrayInputStream

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
