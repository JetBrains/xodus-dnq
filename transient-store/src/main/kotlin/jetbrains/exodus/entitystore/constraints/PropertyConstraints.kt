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
package jetbrains.exodus.entitystore.constraints

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import org.apache.commons.lang3.StringUtils
import java.net.MalformedURLException
import java.net.URL
import java.util.regex.Pattern

open class alpha(var message: String = "should contain only letters") : PropertyConstraint<String?>() {
    override fun isValid(value: String?): Boolean {
        return value == null || StringUtils.isAlpha(value)
    }

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return String.format("%s should contain only aplpha characters but was %s", propertyName, propertyValue)
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?) = message
}

open class numeric(var message: String = "should contain only digits") : PropertyConstraint<String?>() {
    override fun isValid(value: String?): Boolean {
        return value == null || StringUtils.isNumeric(value)
    }

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return String.format("%s should contain only numeric characters but was %s", propertyName, propertyValue)
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?) = message
}


open class alphaNumeric(var message: String = "should contain only letters and digits") : PropertyConstraint<String?>() {
    override fun isValid(value: String?): Boolean {
        return value == null || StringUtils.isAlphanumeric(value)
    }

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return String.format("%s should contain only alpha and numeric characters but was %s", propertyName, propertyValue)
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?) = message
}

open class containsNone(var chars: String = "", var message: String = "shouldn't contain characters %s") : PropertyConstraint<String?>() {
    override fun isValid(value: String?): Boolean {
        return StringUtils.containsNone(value, chars)
    }

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return String.format("%s shouldn't contain chars %s but was %s", propertyName, toCommaSeparatedList(), propertyValue)
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?): String {
        return String.format(message, toCommaSeparatedList())
    }

    private fun toCommaSeparatedList(): String {
        val builder = StringBuilder(chars.length * 5 - 2)
        var first = true
        for (i in 0..chars.length - 1) {
            if (first) {
                first = false
            } else {
                builder.append(", ")
            }
            builder.append('\'').append(chars[i]).append('\'')
        }
        return builder.toString()
    }
}

open class url(var message: String = "is not a valid URL") : PropertyConstraint<String?>() {
    override fun isValid(propertyValue: String?): Boolean {
        if (propertyValue == null) {
            return true
        }
        try {
            URL(propertyValue)
            return true
        } catch (e: MalformedURLException) {
            return false
        }

    }

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return String.format("%s should be valid url but was %s", propertyName, propertyValue)
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?) = message
}

open class regexp(var pattern: Pattern, var message: String = "wrong value format") : PropertyConstraint<String?>() {
    override fun isValid(value: String?): Boolean {
        return value == null || pattern.matcher(value).matches()
    }

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return String.format("%s should match pattern %s but was %s", propertyName, pattern.toString(), propertyValue)
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?): String = message
}

internal val EMAIL_PATTERN = Pattern.compile("[\\w\\-]+(?:[\\+\\.][\\w\\-]+)*@(?:[\\w\\-]+\\.)+[a-z]{2,}", 2)

open class email(message: String = "is not a valid email") : regexp(EMAIL_PATTERN, message) {

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return String.format("%s should be valid email but was %s", propertyName, propertyValue)
    }
}

open class length(
        var min: Int = 0,
        var max: Int = 0,
        var minMessage: String = "should be at least %d characters long",
        var maxMessage: String = "should be at most %d characters long",
        var rangeMessage: String = "should be at from %d to %d characters long"
) : PropertyConstraint<String?>() {
    override fun isValid(value: String?): Boolean {
        return StringUtils.length(value) in min..max
    }

    override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
        return String.format("Length of %s should be in range [%d, %d] but was [%d]", propertyName, min, max, StringUtils.length(propertyValue))
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: String?): String {
        val message: String
        if (min > 0 && max < Integer.MAX_VALUE) {
            message = String.format(rangeMessage, min, max)
        } else if (min > 0) {
            message = String.format(minMessage, min)
        } else {
            message = String.format(maxMessage, max)
        }
        return message
    }
}

open class inRange<T : Number?>(
        var min: Long = 0,
        var max: Long = Long.MAX_VALUE,
        var minMessage: String = "should be at least %d",
        var maxMessage: String = "should be at most %d",
        var rangeMessage: String = "should be in range from %d to %d"

) : PropertyConstraint<T>() {

    override fun isValid(value: T): Boolean {
        if (value == null) {
            return true
        }
        return value.toLong() in min..max
    }

    override fun getExceptionMessage(propertyName: String, propertyValue: T): String {
        return String.format("%s should be in range [%d, %d] but was %d", propertyName, min, max, propertyValue)
    }

    override fun getDisplayMessage(propertyName: String, propertyValue: T): String {
        val message: String
        if (min > 0 && max < Long.MAX_VALUE) {
            message = String.format(rangeMessage, min, max)
        } else if (min > 0) {
            message = String.format(minMessage, min)
        } else {
            message = String.format(maxMessage, max)
        }
        return message
    }
}
