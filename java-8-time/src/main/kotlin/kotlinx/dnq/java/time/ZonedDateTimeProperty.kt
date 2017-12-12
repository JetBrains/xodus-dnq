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
import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.Constraints
import kotlinx.dnq.simple.DEFAULT_REQUIRED
import kotlinx.dnq.simple.custom.type.XdCustomTypeBinding
import kotlinx.dnq.simple.custom.type.readString
import kotlinx.dnq.simple.xdCachedProp
import kotlinx.dnq.simple.xdNullableCachedProp
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

private val min = ZonedDateTime.ofLocal(LocalDateTime.MIN, ZoneOffset.UTC, ZoneOffset.UTC)
private val max = ZonedDateTime.ofLocal(LocalDateTime.MAX, ZoneOffset.UTC, ZoneOffset.UTC)

object ZonedDateTimeBinding : XdCustomTypeBinding<ZonedDateTime>() {

    override fun write(stream: LightOutputStream, value: ZonedDateTime) {
        LocalDateTimeBinding.write(stream, value.toLocalDateTime())
        stream.writeString(value.zone.id)
    }

    override fun read(stream: ByteArrayInputStream): ZonedDateTime {
        return ZonedDateTime.of(LocalDateTimeBinding.read(stream), ZoneId.of(stream.readString()))
    }

    override fun min(): ZonedDateTime = min
    override fun max(): ZonedDateTime = max
    override fun prev(value: ZonedDateTime): ZonedDateTime = value.minusNanos(1)
    override fun next(value: ZonedDateTime): ZonedDateTime = value.plusNanos(1)
}

fun <R : XdEntity> xdZonedDateTimeProp(dbName: String? = null, constraints: Constraints<R, ZonedDateTime?>? = null) =
        xdNullableCachedProp(dbName, ZonedDateTimeBinding, constraints)

fun <R : XdEntity> xdRequiredZonedDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, ZonedDateTime?>? = null) =
        xdCachedProp(dbName, constraints, true, unique, ZonedDateTimeBinding, DEFAULT_REQUIRED)
