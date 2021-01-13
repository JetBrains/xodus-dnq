/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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

/**
 * Adds constraint for [Temporal] primitive persistent property.
 * The property value should be more than the value returned by the given closure.
 *
 * Constrains are checked on transaction flush. Xodus-DNQ throws `ConstraintsValidationException` if constraint check
 * fails. Method `getCauses()` of `ConstraintsValidationException` returns all actual
 * `DataIntegrityViolationException`s corresponding to data validation errors that happen during the transaction flush.
 * ```
 * try {
 *     store.transactional { /* Do some database update */ }
 * } catch(e: ConstraintsValidationException) {
 *     e.causes.forEach { e.printStackTrace() }
 * }
 * ```
 *
 * **Sample**
 * ```
 * var afterDomini by xdInstantProp { isAfter({ domini }) }
 * ```
 *
 * @param time closure that returns date-time that should be less than the value of the property
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun <V> PropertyConstraintBuilder<*, V?>.isAfter(time: () -> V, message: String = "is not after ${time()}")
        where V : Comparable<V>,
              V : Temporal {

    constraints.add(object : PropertyConstraint<V?>() {
        override fun isValid(value: V?): Boolean {
            return value == null || value > time()
        }

        override fun getExceptionMessage(propertyName: String, propertyValue: V?) =
                "$propertyName should be after ${time()} but was $propertyValue"

        override fun getDisplayMessage(propertyName: String, propertyValue: V?) = message
    })
}

/**
 * Adds constraint for [Temporal] primitive persistent property.
 * The property value should be less than the value returned by the given closure.
 *
 * Constrains are checked on transaction flush. Xodus-DNQ throws `ConstraintsValidationException` if constraint check
 * fails. Method `getCauses()` of `ConstraintsValidationException` returns all actual
 * `DataIntegrityViolationException`s corresponding to data validation errors that happen during the transaction flush.
 * ```
 * try {
 *     store.transactional { /* Do some database update */ }
 * } catch(e: ConstraintsValidationException) {
 *     e.causes.forEach { e.printStackTrace() }
 * }
 * ```
 *
 * **Sample**
 * ```
 * var beforeChrist by xdInstantProp { isBefore({ domini }) }
 * ```
 *
 * @param time closure that returns date-time that should be greater than the value of the property
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun <V> PropertyConstraintBuilder<*, V?>.isBefore(time: () -> V, message: String = "is not before ${time()}")
        where V : Comparable<V>,
              V : Temporal {

    constraints.add(object : PropertyConstraint<V?>() {
        override fun isValid(value: V?): Boolean {
            return value == null || value < time()
        }

        override fun getExceptionMessage(propertyName: String, propertyValue: V?) =
                "$propertyName should be before ${time()} but was $propertyValue"

        override fun getDisplayMessage(propertyName: String, propertyValue: V?) = message
    })
}
