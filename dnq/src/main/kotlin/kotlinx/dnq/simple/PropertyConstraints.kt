/**
 * Copyright 2006 - 2019 JetBrains s.r.o.
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
package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.exceptions.SimplePropertyValidationException
import jetbrains.exodus.entitystore.constraints.inRange
import jetbrains.exodus.entitystore.constraints.regexp
import jetbrains.exodus.query.metadata.PropertyMetaData
import kotlinx.dnq.XdEntity
import kotlinx.dnq.wrapper
import org.joda.time.DateTime
import java.net.URI
import java.net.URISyntaxException

class PropertyConstraintBuilder<R : XdEntity, T>() {
    val constraints = mutableListOf<PropertyConstraint<T>>()
}

/**
 * Adds constraint for String primitive persistent property.
 * The property value should match provided regular expression.
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
 *
 * ```
 * **Sample**
 * ```
 * var javaIdentifier by xdStringProp {
 *     regex(Regex("[A-Za-z][A-Za-z0-9_]*"), "is not a valid Java identifier")
 * }
 * ```
 *
 * @param pattern regular expression that should be matched by the value
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, String?>.regex(pattern: Regex, message: String? = null) {
    constraints.add(regexp(pattern = pattern.toPattern()).apply {
        if (message != null) {
            this.message = message
        }
    })
}

/**
 * Adds constraint for String primitive persistent property.
 * The property value should be a valid email address.
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
 *
 * ```
 * **Sample**
 * ```
 * var email by xdStringProp { email() }
 * ```
 *
 * @param pattern optional custom regular expression to verify email.
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, String?>.email(pattern: Regex? = null, message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.email().apply {
        if (pattern != null) {
            this.pattern = pattern.toPattern()
        }
        if (message != null) {
            this.message = message
        }
    })
}

/**
 * Adds constraint for String primitive persistent property.
 * The property value should contain none of the specified characters.
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
 *
 * ```
 * **Sample**
 * ```
 * var noDash by xdStringProp { containsNone("-") }
 * ```
 *
 * @param chars string containing prohibited chars
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, String?>.containsNone(chars: String, message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.containsNone().apply {
        this.chars = chars
        if (message != null) {
            this.message = message
        }
    })
}

/**
 * Adds constraint for String primitive persistent property.
 * The property value should contain only letter characters.
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
 *
 * ```
 * **Sample**
 * ```
 * var alpha by xdStringProp { alpha() }
 * ```
 *
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, String?>.alpha(message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.alpha().apply {
        if (message != null) {
            this.message = message
        }
    })
}

/**
 * Adds constraint for String primitive persistent property.
 * The property value should contain only digit characters.
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
 *
 * ```
 * **Sample**
 * ```
 * var number by xdStringProp { numeric() }
 * ```
 *
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, String?>.numeric(message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.numeric().apply {
        if (message != null) {
            this.message = message
        }
    })
}

/**
 * Adds constraint for String primitive persistent property.
 * The property value should contain only letter or digit characters.
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
 *
 * ```
 * **Sample**
 * ```
 * var base64 by xdStringProp { alphaNumeric() }
 * ```
 *
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, String?>.alphaNumeric(message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.alphaNumeric().apply {
        if (message != null) {
            this.message = message
        }
    })
}

/**
 * Adds constraint for String primitive persistent property.
 * The property value should be a valid [URL](https://en.wikipedia.org/wiki/URL).
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
 * var url by xdStringProp { url() }
 * ```
 *
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, String?>.url(message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.url().apply {
        if (message != null) {
            this.message = message
        }
    })
}

/**
 * Adds constraint for String primitive persistent property.
 * The property value should be a valid [URI](https://en.wikipedia.org/wiki/Uniform_Resource_Identifier).
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
 * var uri by xdStringProp { uri() }
 * ```
 *
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, String?>.uri(message: String? = null) {
    constraints.add(object : PropertyConstraint<String?>() {
        var message = message ?: "is not a valid URI"

        override fun isValid(propertyValue: String?): Boolean {
            return if (propertyValue != null) {
                try {
                    URI(propertyValue)
                    true
                } catch (e: URISyntaxException) {
                    false
                }
            } else true
        }

        override fun getExceptionMessage(propertyName: String, propertyValue: String?) =
                "$propertyName should be valid URI but was $propertyValue"

        override fun getDisplayMessage(propertyName: String, propertyValue: String?) =
                this.message
    })
}

/**
 * Adds constraint for String primitive persistent property.
 * The property value length should fall into defined range.
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
 * var badPassword by xdStringProp { length(min = 5, max = 10) }
 * ```
 *
 * @param min minimal length of the property value. Is 0 by default.
 * @param max maximal length of the property value. It not limited by default.
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, String?>.length(min: Int = 0, max: Int = Int.MAX_VALUE, message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.length().apply {
        if (min > 0) {
            this.min = min
        }
        if (max < Int.MAX_VALUE) {
            this.max = max
        }
        if (message != null) {
            when {
                min > 0 && max < Int.MAX_VALUE -> this.rangeMessage = message
                min > 0 -> this.minMessage = message
                max < Int.MAX_VALUE -> this.maxMessage = message
            }
        }
    })
}

class RequireIfConstraint<R : XdEntity, T>(val message: String?, val predicate: R.() -> Boolean) : PropertyConstraint<T>() {
    override fun check(entity: TransientEntity, propertyMetaData: PropertyMetaData, value: T): SimplePropertyValidationException? {
        @Suppress("UNCHECKED_CAST")
        return if (value == null && (entity.wrapper as R).predicate()) {
            val propertyName = propertyMetaData.name
            SimplePropertyValidationException(getExceptionMessage(propertyName, value), getDisplayMessage(propertyName, value), entity, propertyName)
        } else {
            null
        }
    }

    override fun isValid(value: T): Boolean {
        throw UnsupportedOperationException()
    }

    override fun getExceptionMessage(propertyName: String, propertyValue: T): String {
        return "Value for $propertyName is required"
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: T) = message ?: "required"
}

/**
 * Adds constraint for primitive persistent property.
 * The property value is required if the given closure returns `true`.
 * Note that the constraint is checked only if **the property** is updated.
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
 * var main by xdStringProp()
 * var dependent by xdLongProp { requireIf { main != null } }
 * ```
 *
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 * @param predicate if `true` the property is required.
 */
fun <R : XdEntity, T> PropertyConstraintBuilder<R, T>.requireIf(message: String? = null, predicate: R.() -> Boolean) {
    constraints.add(RequireIfConstraint<R, T>(message, predicate))
}

/**
 * Adds constraint for Number primitive persistent property.
 * The property value should be more or equals to the given value.
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
 * var timeout by xdIntProp { min(1000) }
 * ```
 *
 * @param min minimal property value
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun <T : Number?> PropertyConstraintBuilder<*, T>.min(min: Long, message: String? = null) {
    constraints.add(inRange<T>().apply {
        this.min = min
        if (message != null) {
            this.minMessage = message
        }
    })
}

/**
 * Adds constraint for Number primitive persistent property.
 * The property value should be less or equals to the given value.
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
 * var timeout by xdIntProp { max(10_000) }
 * ```
 *
 * @param max maximal property value
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun <T : Number?> PropertyConstraintBuilder<*, T>.max(max: Long, message: String? = null) {
    constraints.add(inRange<T>().apply {
        this.max = max
        if (message != null) {
            this.maxMessage = message
        }
    })
}

/**
 * Adds constraint for org.joda.time.DateTime primitive persistent property.
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
 * var afterDomini by xdDateTimeProp { isAfter({ domini }) }
 * ```
 *
 * @param dateTime closure that returns date-time that should be less than the value of the property
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, DateTime?>.isAfter(dateTime: () -> DateTime, message: String = "is not after ${dateTime()}") {
    constraints.add(object : PropertyConstraint<DateTime?>() {
        override fun isValid(propertyValue: DateTime?): Boolean {
            return propertyValue == null || propertyValue.isAfter(dateTime())
        }

        override fun getExceptionMessage(propertyName: String, propertyValue: DateTime?) =
                "$propertyName should be after ${dateTime()} but was $propertyValue"

        override fun getDisplayMessage(propertyName: String, propertyValue: DateTime?) = message
    })
}

/**
 * Adds constraint for org.joda.time.DateTime primitive persistent property.
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
 * var beforeChrist by xdDateTimeProp { isBefore({ domini }) }
 * ```
 *
 * @param dateTime closure that returns date-time that should be more than the value of the property
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, DateTime?>.isBefore(dateTime: () -> DateTime, message: String = "is not before ${dateTime()}") {
    constraints.add(object : PropertyConstraint<DateTime?>() {
        override fun isValid(propertyValue: DateTime?): Boolean {
            return propertyValue == null || propertyValue.isBefore(dateTime())
        }

        override fun getExceptionMessage(propertyName: String, propertyValue: DateTime?) =
                "$propertyName should be before ${dateTime()} but was $propertyValue"

        override fun getDisplayMessage(propertyName: String, propertyValue: DateTime?) = message
    })
}

/**
 * Adds constraint for org.joda.time.DateTime primitive persistent property.
 * The property value should be a moment in future (relative to the moment of the check).
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
 * var future by xdDateTimeProp { future() }
 * ```
 *
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, DateTime?>.past(message: String = "is not in the past") {
    constraints.add(object : PropertyConstraint<DateTime?>() {
        override fun isValid(propertyValue: DateTime?): Boolean {
            return propertyValue == null || propertyValue.isBeforeNow
        }

        override fun getExceptionMessage(propertyName: String, propertyValue: DateTime?) =
                "$propertyName should be in the past (before ${DateTime.now()}) but was $propertyValue"

        override fun getDisplayMessage(propertyName: String, propertyValue: DateTime?) = message
    })
}

/**
 * Adds constraint for org.joda.time.DateTime primitive persistent property.
 * The property value should be a moment in past (relative to the moment of the check).
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
 * var past by xdDateTimeProp { past() }
 * ```
 *
 * @param message optional error message that will be returned from
 *        [jetbrains.exodus.database.exceptions.DataIntegrityViolationException#getDisplayMessage]
 */
fun PropertyConstraintBuilder<*, DateTime?>.future(message: String = "is not in the future") {
    constraints.add(object : PropertyConstraint<DateTime?>() {
        override fun isValid(propertyValue: DateTime?): Boolean {
            return propertyValue == null || propertyValue.isAfterNow
        }

        override fun getExceptionMessage(propertyName: String, propertyValue: DateTime?) =
                "$propertyName should be in the future (after ${DateTime.now()}) but was $propertyValue"

        override fun getDisplayMessage(propertyName: String, propertyValue: DateTime?) = message
    })
}
