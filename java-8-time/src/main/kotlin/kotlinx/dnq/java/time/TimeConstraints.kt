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

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import kotlinx.dnq.simple.PropertyConstraintBuilder
import java.time.temporal.Temporal

fun <V> PropertyConstraintBuilder<*, V?>.isAfter(dateTime: () -> V, message: String = "is not after ${dateTime()}")
        where V : Comparable<V>,
              V : Temporal {

    constraints.add(object : PropertyConstraint<V?>() {
        override fun isValid(propertyValue: V?): Boolean {
            return propertyValue == null || propertyValue > dateTime()
        }

        override fun getExceptionMessage(propertyName: String, propertyValue: V?) =
                "$propertyName should be after ${dateTime()} but was $propertyValue"

        override fun getDisplayMessage(propertyName: String, propertyValue: V?) = message
    })
}

fun <V> PropertyConstraintBuilder<*, V?>.isBefore(dateTime: () -> V, message: String = "is not before ${dateTime()}")
        where V : Comparable<V>,
              V : Temporal {

    constraints.add(object : PropertyConstraint<V?>() {
        override fun isValid(propertyValue: V?): Boolean {
            return propertyValue == null || propertyValue < dateTime()
        }

        override fun getExceptionMessage(propertyName: String, propertyValue: V?) =
                "$propertyName should be before ${dateTime()} but was $propertyValue"

        override fun getDisplayMessage(propertyName: String, propertyValue: V?) = message
    })
}
