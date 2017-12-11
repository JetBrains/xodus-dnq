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
import java.time.LocalDate

object LocalDateBinding : XdComparableBinding<LocalDate>() {
    override fun write(stream: LightOutputStream, value: LocalDate) {
        stream.writeInt(value.year)
        stream.writeShort(value.monthValue.toShort())
        stream.writeShort(value.dayOfMonth.toShort())
    }

    override fun read(stream: ByteArrayInputStream): LocalDate {
        val year = stream.readInt()
        val month = stream.readShort()
        val day = stream.readShort()
        return LocalDate.of(year, month.toInt(), day.toInt())
    }
}

fun <R : XdEntity> xdLocalDateProp(dbName: String? = null, constraints: Constraints<R, LocalDate?>? = null) =
        XdPropertyCachedProvider {
            XdNullableProperty<R, LocalDate>(
                    LocalDate::class.java,
                    dbName,
                    constraints.collect()
            )
        }

fun <R : XdEntity> xdRequiredLocalDateProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, LocalDate?>? = null) =
        XdPropertyCachedProvider {
            XdProperty<R, LocalDate>(
                    LocalDate::class.java,
                    dbName,
                    constraints.collect(),
                    if (unique) XdPropertyRequirement.UNIQUE else XdPropertyRequirement.REQUIRED,
                    { e, p -> throw RequiredPropertyUndefinedException(e, p) }
            )
        }
