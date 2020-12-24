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
package jetbrains.exodus.entitystore.constraints

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import java.net.MalformedURLException
import java.net.URL
import java.util.regex.Pattern

open class alpha(var message: String = "should contain only letters") : PropertyConstraint<String?>() {
    override fun isValid(value: String?) = value == null || value.all { it.isLetter() }

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return "$propertyName should contain only alpha characters but was $propertyValue"
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?) = message
}

open class numeric(var message: String = "should contain only digits") : PropertyConstraint<String?>() {
    override fun isValid(value: String?) = value == null || value.all { it.isDigit() }

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return "$propertyName should contain only numeric characters but was $propertyValue"
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?) = message
}


open class alphaNumeric(var message: String = "should contain only letters and digits") : PropertyConstraint<String?>() {
    override fun isValid(value: String?) = value == null || value.all { it.isLetterOrDigit() }

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return "$propertyName should contain only alpha and numeric characters but was $propertyValue"
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?) = message
}

open class containsNone(var chars: String = "", var message: String = "shouldn't contain characters %s") : PropertyConstraint<String?>() {
    override fun isValid(value: String?) = containsNone(value, *chars.toCharArray())

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return "$propertyName shouldn't contain chars ${toCommaSeparatedList()} but was $propertyValue"
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?): String {
        return String.format(message, toCommaSeparatedList())
    }

    private fun toCommaSeparatedList(): String {
        return chars.asSequence().joinToString { "'$it'" }
    }
}

class notBlank(var message: String = "shouldn't be blank") : PropertyConstraint<String?>() {
    override fun isValid(value: String?): Boolean = !value.isNullOrBlank()

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return "$propertyName shouldn't be blank"
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?) = message
}

open class url(var message: String = "is not a valid URL") : PropertyConstraint<String?>() {
    override fun isValid(value: String?): Boolean {
        if (value == null) {
            return true
        }
        return try {
            URL(value)
            true
        } catch (e: MalformedURLException) {
            false
        }

    }

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return "$propertyName should be valid url but was $propertyValue"
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?) = message
}

open class regexp(var pattern: Pattern, var message: String = "wrong value format") : PropertyConstraint<String?>() {
    override fun isValid(value: String?) = value == null || pattern.matcher(value).matches()

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return "$propertyName should match pattern $pattern but was $propertyValue"
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?): String = message
}

internal val EMAIL_PATTERN = Pattern.compile("[\\w\\-]+(?:[\\+\\.][\\w\\-]+)*@(?:[\\w\\-]+\\.)+[a-z]{2,}", 2)

open class email(message: String = "is not a valid email") : regexp(EMAIL_PATTERN, message) {

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return "$propertyName should be valid email but was $propertyValue"
    }
}

open class length(
        var min: Int = 0,
        var max: Int = Int.MAX_VALUE,
        var minMessage: String = "should be at least %d characters long",
        var maxMessage: String = "should be at most %d characters long",
        var rangeMessage: String = "should be from %d to %d characters long"
) : PropertyConstraint<String?>() {

    override fun isValid(value: String?) = (value?.length ?: 0) in min..max

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return "Length of $propertyName should be in range [$min, $max] but was [${propertyValue?.length ?: 0}]"
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?): String {
        return if (min > 0 && max < Int.MAX_VALUE) {
            String.format(rangeMessage, min, max)
        } else if (min > 0) {
            String.format(minMessage, min)
        } else {
            String.format(maxMessage, max)
        }
    }
}

open class inRange<T : Number?>(
        var min: Long = 0,
        var max: Long = Long.MAX_VALUE,
        var minMessage: String = "should be at least %d",
        var maxMessage: String = "should be at most %d",
        var rangeMessage: String = "should be in range from %d to %d"

) : PropertyConstraint<T>() {

    override fun isValid(value: T) = value == null || value.toLong() in min..max

    override fun getExceptionMessage(propertyName: String, propertyValue: T): String {
        return "$propertyName should be in range [$min, $max] but was $propertyValue"
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: T): String {
        return if (min > 0 && max < Long.MAX_VALUE) {
            String.format(rangeMessage, min, max)
        } else if (min > 0) {
            String.format(minMessage, min)
        } else {
            String.format(maxMessage, max)
        }
    }
}

internal fun containsNone(value: String?, vararg searchChars: Char): Boolean {
    if (value == null) {
        return true
    }

    val length = value.length
    val lastIndex = length - 1
    val searchRange = searchChars.indices
    val lastSearchIndex = searchChars.size - 1

    (0 until length).forEach { i ->
        val ch = value[i]
        searchRange.forEach { j ->
            if (searchChars[j] == ch) {
                if (Character.isHighSurrogate(ch)) {
                    if (j == lastSearchIndex) {
                        return false
                    }
                    if (i < lastIndex && searchChars[j + 1] == value[i + 1]) {
                        return false
                    }
                } else {
                    return false
                }
            }
        }
    }

    return true
}
