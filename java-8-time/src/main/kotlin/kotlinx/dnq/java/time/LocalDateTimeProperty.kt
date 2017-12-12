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
import kotlinx.dnq.simple.custom.type.XdComparableBinding
import kotlinx.dnq.util.XdPropertyCachedProvider
import java.io.ByteArrayInputStream
import java.time.LocalDateTime

object LocalDateTimeBinding : XdComparableBinding<LocalDateTime>() {
    override fun write(stream: LightOutputStream, value: LocalDateTime) {
        LocalDateBinding.write(stream, value.toLocalDate())
        LocalTimeBinding.write(stream, value.toLocalTime())
    }

    override fun read(stream: ByteArrayInputStream): LocalDateTime {
        val date = LocalDateBinding.read(stream)
        val time = LocalTimeBinding.read(stream)
        return LocalDateTime.of(date, time)
    }
}

fun <R : XdEntity> xdLocalDateTimeProp(dbName: String? = null, constraints: Constraints<R, LocalDateTime?>? = null) =
        XdPropertyCachedProvider {
            XdNullableProperty<R, LocalDateTime>(
                    LocalDateTime::class.java,
                    dbName,
                    constraints.collect(),
                    LocalDateTimeBinding
            )
        }

fun <R : XdEntity> xdRequiredLocalDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, LocalDateTime?>? = null) =
        XdPropertyCachedProvider {
            XdProperty<R, LocalDateTime>(
                    LocalDateTime::class.java,
                    dbName,
                    constraints.collect(),
                    if (unique) XdPropertyRequirement.UNIQUE else XdPropertyRequirement.REQUIRED,
                    DEFAULT_REQUIRED,
                    LocalDateTimeBinding
            )
        }
