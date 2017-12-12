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

import jetbrains.exodus.util.LightOutputStream
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.*
import kotlinx.dnq.simple.custom.type.*
import kotlinx.dnq.util.XdPropertyCachedProvider
import java.io.ByteArrayInputStream
import java.time.LocalTime

object LocalTimeBinding : XdComparableBinding<LocalTime>() {

    override fun write(stream: LightOutputStream, value: LocalTime) {
        stream.writeByte(value.hour.toByte())
        stream.writeByte(value.minute.toByte())
        stream.writeByte(value.second.toByte())
        stream.writeInt(value.nano)
    }

    override fun read(stream: ByteArrayInputStream): LocalTime {
        val hour = stream.readByte()
        val minute = stream.readByte()
        val second = stream.readByte()
        val nano = stream.readInt()
        return LocalTime.of(hour.toInt(), minute.toInt(), second.toInt(), nano)
    }
}

fun <R : XdEntity> xdLocalTimeProp(dbName: String? = null, constraints: Constraints<R, LocalTime?>? = null) =
        XdPropertyCachedProvider {
            XdNullableProperty<R, LocalTime>(
                    LocalTime::class.java,
                    dbName,
                    constraints.collect()
            )
        }

fun <R : XdEntity> xdRequiredLocalTimeProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, LocalTime?>? = null) =
        XdPropertyCachedProvider {
            XdProperty<R, LocalTime>(
                    LocalTime::class.java,
                    dbName,
                    constraints.collect(),
                    if (unique) XdPropertyRequirement.UNIQUE else XdPropertyRequirement.REQUIRED,
                    { e, p -> throw RequiredPropertyUndefinedException(e, p) }
            )
        }
