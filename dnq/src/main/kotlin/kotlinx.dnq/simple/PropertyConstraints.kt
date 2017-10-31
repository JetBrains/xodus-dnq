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

fun PropertyConstraintBuilder<*, String?>.regex(pattern: Regex, message: String? = null) {
    constraints.add(regexp(pattern = pattern.toPattern()).apply {
        if (message != null) {
            this.message = message
        }
    })
}

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

fun PropertyConstraintBuilder<*, String?>.containsNone(chars: String, message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.containsNone().apply {
        this.chars = chars
        if (message != null) {
            this.message = message
        }
    })
}

fun PropertyConstraintBuilder<*, String?>.alpha(message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.alpha().apply {
        if (message != null) {
            this.message = message
        }
    })
}

fun PropertyConstraintBuilder<*, String?>.numeric(message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.numeric().apply {
        if (message != null) {
            this.message = message
        }
    })
}

fun PropertyConstraintBuilder<*, String?>.alphaNumeric(message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.alphaNumeric().apply {
        if (message != null) {
            this.message = message
        }
    })
}

fun PropertyConstraintBuilder<*, String?>.url(message: String? = null) {
    constraints.add(jetbrains.exodus.entitystore.constraints.url().apply {
        if (message != null) {
            this.message = message
        }
    })
}

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

class RequireIfConstraint<R : XdEntity, T>(val message: String?, val predicate: R.() -> Boolean) : PropertyConstraint<T>() {
    override fun check(e: TransientEntity, pmd: PropertyMetaData, value: T): SimplePropertyValidationException? {
        @Suppress("UNCHECKED_CAST")
        return if (value == null && (e.wrapper as R).predicate()) {
            val propertyName = pmd.name
            SimplePropertyValidationException(getExceptionMessage(propertyName, value), getDisplayMessage(propertyName, value), e, propertyName)
        } else {
            null
        }
    }

    override fun isValid(value: T): Boolean {
        throw UnsupportedOperationException()
    }

    override fun getExceptionMessage(propertyName: String?, propertyValue: T): String {
        return "Value for $propertyName is required"
    }

    override fun getDisplayMessage(propertyName: String?, propertyValue: T) = message ?: "required"
}

fun <R : XdEntity, T> PropertyConstraintBuilder<R, T>.requireIf(message: String? = null, predicate: R.() -> Boolean) {
    constraints.add(RequireIfConstraint<R, T>(message, predicate))
}

fun <T : Number?> PropertyConstraintBuilder<*, T>.min(min: Long, message: String? = null) {
    constraints.add(inRange<T>().apply {
        this.min = min
        if (message != null) {
            this.minMessage = message
        }
    })
}

fun <T : Number?> PropertyConstraintBuilder<*, T>.max(max: Long, message: String? = null) {
    constraints.add(inRange<T>().apply {
        this.max = max
        if (message != null) {
            this.maxMessage = message
        }
    })
}

fun <T : DateTime?> PropertyConstraintBuilder<*, DateTime?>.isAfter(dateTime: () -> DateTime, message: String = "is not after $dateTime") {
    constraints.add(object : PropertyConstraint<DateTime?>() {
        override fun isValid(propertyValue: DateTime?): Boolean {
            return propertyValue == null || propertyValue.isAfter(dateTime())
        }

        override fun getExceptionMessage(propertyName: String, propertyValue: DateTime?) =
                "$propertyName should be after ${dateTime()} but was $propertyValue"

        override fun getDisplayMessage(propertyName: String, propertyValue: DateTime?) = message
    })
}

fun <T : DateTime?> PropertyConstraintBuilder<*, DateTime?>.isBefore(dateTime: () -> DateTime, message: String = "is not before $dateTime") {
    constraints.add(object : PropertyConstraint<DateTime?>() {
        override fun isValid(propertyValue: DateTime?): Boolean {
            return propertyValue == null || propertyValue.isBefore(dateTime())
        }

        override fun getExceptionMessage(propertyName: String, propertyValue: DateTime?) =
                "$propertyName should be before ${dateTime()} but was $propertyValue"

        override fun getDisplayMessage(propertyName: String, propertyValue: DateTime?) = message
    })
}

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
