/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
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

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.XdNaturalEntityType
import java.time.*
import kotlin.reflect.KMutableProperty1

class TimePropertiesTestData<XD : XdEntity, V : Comparable<V>>(
        val entityType: XdEntityType<XD>,
        val optionalProperty: KMutableProperty1<XD, V?>,
        val requiredProperty: KMutableProperty1<XD, V>,
        val someValue: () -> V,
        val greaterValue: (V) -> V,
        val lessValue: (V) -> V) {

    override fun toString(): String {
        return someValue().javaClass.simpleName
    }
}

class InstantPropertyTestEntity(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<InstantPropertyTestEntity>()

    var property by xdInstantProp()
    var propertyRequired by xdRequiredInstantProp()
}

class LocalDatePropertyTestEntity(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<LocalDatePropertyTestEntity>()

    var property by xdLocalDateProp()
    var propertyRequired by xdRequiredLocalDateProp()
}

class LocalTimePropertyTestEntity(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<LocalTimePropertyTestEntity>()

    var property by xdLocalTimeProp()
    var propertyRequired by xdRequiredLocalTimeProp()
}

class LocalDateTimePropertyTestEntity(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<LocalDateTimePropertyTestEntity>()

    var property by xdLocalDateTimeProp { isAfter({ LocalDateTime.now().minusYears(1) }) }
    var propertyRequired by xdRequiredLocalDateTimeProp()
}

class ZoneOffsetPropertyTestEntity(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<ZoneOffsetPropertyTestEntity>()

    var property by xdZoneOffsetProp()
    var propertyRequired by xdRequiredZoneOffsetProp()
}

class OffsetTimePropertyTestEntity(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<OffsetTimePropertyTestEntity>()

    var property by xdOffsetTimeProp()
    var propertyRequired by xdRequiredOffsetTimeProp()
}

class OffsetDateTimePropertyTestEntity(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<OffsetDateTimePropertyTestEntity>()

    var property by xdOffsetDateTimeProp()
    var propertyRequired by xdRequiredOffsetDateTimeProp()
}

class ZonedDateTimePropertyTestEntity(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<ZonedDateTimePropertyTestEntity>()

    var property by xdZonedDateTimeProp()
    var propertyRequired by xdRequiredZonedDateTimeProp()
}

val timePropertiesTestData = listOf(
        TimePropertiesTestData(
                InstantPropertyTestEntity,
                InstantPropertyTestEntity::property,
                InstantPropertyTestEntity::propertyRequired,
                { Instant.now() }, { it.plusSeconds(10) }, { it.minusSeconds(10) }
        ),
        TimePropertiesTestData(
                LocalDatePropertyTestEntity,
                LocalDatePropertyTestEntity::property,
                LocalDatePropertyTestEntity::propertyRequired,
                { LocalDate.now() }, { it.plusDays(10) }, { it.minusDays(10) }
        ),
        TimePropertiesTestData(
                LocalTimePropertyTestEntity,
                LocalTimePropertyTestEntity::property,
                LocalTimePropertyTestEntity::propertyRequired,
                { LocalTime.now() }, { it.plusMinutes(10) }, { it.minusMinutes(10) }
        ),
        TimePropertiesTestData(
                LocalDateTimePropertyTestEntity,
                LocalDateTimePropertyTestEntity::property,
                LocalDateTimePropertyTestEntity::propertyRequired,
                { LocalDateTime.now() }, { it.plusMinutes(10) }, { it.minusMinutes(10) }
        ),
        TimePropertiesTestData(
                ZoneOffsetPropertyTestEntity,
                ZoneOffsetPropertyTestEntity::property,
                ZoneOffsetPropertyTestEntity::propertyRequired,
                { ZoneOffset.ofHours(0) }, { ZoneOffset.ofHours(-2) }, { ZoneOffset.ofHours(+2) }
        ),
        TimePropertiesTestData(
                OffsetTimePropertyTestEntity,
                OffsetTimePropertyTestEntity::property,
                OffsetTimePropertyTestEntity::propertyRequired,
                { OffsetTime.now() }, { it.plusMinutes(10) }, { it.minusMinutes(10) }
        ),
        TimePropertiesTestData(
                OffsetDateTimePropertyTestEntity,
                OffsetDateTimePropertyTestEntity::property,
                OffsetDateTimePropertyTestEntity::propertyRequired,
                { OffsetDateTime.now() }, { it.plusMinutes(10) }, { it.minusMinutes(10) }
        ),
        TimePropertiesTestData(
                ZonedDateTimePropertyTestEntity,
                ZonedDateTimePropertyTestEntity::property,
                ZonedDateTimePropertyTestEntity::propertyRequired,
                { ZonedDateTime.now() }, { it.plusMinutes(10) }, { it.minusMinutes(10) }
        )
)