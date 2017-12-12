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

import jetbrains.exodus.bindings.*
import jetbrains.exodus.util.LightOutputStream
import java.io.ByteArrayInputStream

fun ByteArrayInputStream.readByte() = (this.read() and 0xFF).toByte()
fun LightOutputStream.writeByte(value: Byte) = this.write(value.toInt())

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
