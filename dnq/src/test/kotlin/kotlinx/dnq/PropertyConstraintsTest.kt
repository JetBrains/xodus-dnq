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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.simple.*
import org.joda.time.DateTime
import org.joda.time.Days
import org.junit.Test
import kotlin.test.assertFailsWith

private val domini = DateTime(-1, 12, 25, 0, 0)

class PropertyConstraintsTest : DBTest() {


    class XdTestEntity(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdTestEntity>()


        var javaIdentifier by xdStringProp {
            regex(Regex("[A-Za-z][A-Za-z0-9_]*"), "Should be valid Java identifier")
        }
        var email by xdStringProp { email() }
        var noDash by xdStringProp { containsNone("-") }
        var alpha by xdStringProp { alpha() }
        var number by xdStringProp { numeric() }
        var base64 by xdStringProp { alphaNumeric() }
        var url by xdStringProp { url() }
        var uri by xdStringProp { uri() }
        var badPassword by xdStringProp { length(min = 5, max = 10) }
        var main by xdStringProp()
        var dependent by xdLongProp { requireIf { main != null } }
        var timeout by xdIntProp { min(1000); max(10_000) }

        var future by xdDateTimeProp { future() }
        var past by xdDateTimeProp { past() }
        var afterDomini by xdDateTimeProp { isAfter({ domini }) }
        var beforeChrist by xdDateTimeProp { isBefore({ domini }) }
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(XdTestEntity)
    }

    @Test
    fun `valid regex`() {
        assertValid {
            javaIdentifier = "javaIdentifier_123"
        }
    }

    @Test
    fun `invalid regex`() {
        assertInvalid("Should be valid Java identifier") {
            javaIdentifier = "123"
        }
    }

    @Test
    fun `valid email`() {
        assertValid {
            email = "user@nowhere.com"
        }
    }

    @Test
    fun `invalid email`() {
        assertInvalid("is not a valid email") {
            email = "user_nowhere"
        }
    }

    @Test
    fun `valid containsNone`() {
        assertValid {
            noDash = "'no dash here'"
        }
    }

    @Test
    fun `invalid containsNone`() {
        assertInvalid("shouldn't contain characters '-'") {
            noDash = "some-dash"
        }
    }

    @Test
    fun `valid alpha`() {
        assertValid {
            alpha = "justLettersЯ"
        }
    }

    @Test
    fun `invalid alpha`() {
        assertInvalid("should contain only letters") {
            alpha = "42"
        }
    }

    @Test
    fun `valid numeric`() {
        assertValid {
            number = "2128506"
        }
    }

    @Test
    fun `invalid numeric`() {
        assertInvalid("should contain only digits") {
            number = "letters"
        }
    }

    @Test
    fun `valid alphaNumeric`() {
        assertValid {
            base64 = "MjEyODUwNg"
        }
    }

    @Test
    fun `invalid alphaNumeric`() {
        assertInvalid("should contain only letters and digits") {
            base64 = "not-only-letter"
        }
    }

    @Test
    fun `valid url`() {
        assertValid {
            url = "https://jetbrains.com"
        }
    }

    @Test
    fun `invalid url`() {
        assertInvalid("is not a valid URL") {
            url = "jetbrains.com"
        }
    }

    @Test
    fun `valid uri`() {
        assertValid {
            uri = "jetbrains.com/youtrack"
        }
    }

    @Test
    fun `valid future`() {
        assertValid {
            future = DateTime.now() + Days.ONE
        }
    }

    @Test
    fun `valid past`() {
        assertValid {
            past = DateTime.now() - Days.ONE
        }
    }

    @Test
    fun `valid isBefore`() {
        assertValid {
            beforeChrist = DateTime(-100, 1, 1, 0, 0)
        }
    }

    @Test
    fun `valid isAfter`() {
        assertValid {
            afterDomini = DateTime(1970, 1, 1, 0, 0)
        }
    }

    @Test
    fun `invalid uri`() {
        assertInvalid("is not a valid URI") {
            uri = "bad URI"
        }
    }

    @Test
    fun `valid length`() {
        assertValid {
            badPassword = "neverGuess"
        }
    }

    @Test
    fun `invalid length`() {
        assertInvalid("should be from 5 to 10 characters long") {
            badPassword = "1234"
        }
    }

    @Test
    fun `invalid length to long`() {
        assertInvalid("should be from 5 to 10 characters long") {
            badPassword = "password123"
        }
    }

    @Test
    fun `valid requireIf`() {
        assertValid {
            url = "https://jetbrains.com"
            dependent = 42L
        }
    }

    @Test
    fun `invalid requireIf`() {
        assertInvalid("Value is required") {
            main = "smth"
        }
    }

    @Test
    fun `valid min and max`() {
        assertValid {
            timeout = 5_000
        }
    }

    @Test
    fun `invalid min`() {
        assertInvalid("should be at least 1000") {
            timeout = 500
        }
    }

    @Test
    fun `invalid max`() {
        assertInvalid("should be at most 10000") {
            timeout = 100_000
        }
    }

    @Test
    fun `invalid future`() {
        assertInvalid("is not in the future") {
            future = DateTime.now() - Days.ONE
        }
    }

    @Test
    fun `invalid past`() {
        assertInvalid("is not in the past") {
            past = DateTime.now() + Days.ONE
        }
    }

    @Test
    fun `invalid isBefore`() {
        assertInvalid("is not before $domini") {
            beforeChrist = DateTime(1970, 1, 1, 0, 0)
        }
    }

    @Test
    fun `invalid isAfter`() {
        assertInvalid("is not after $domini") {
            afterDomini = DateTime(-100, 1, 1, 0, 0)
        }
    }

    private fun assertValid(init: XdTestEntity.() -> Unit) {
        store.transactional {
            XdTestEntity.new {
                badPassword = "12345"
            }.apply(init)
        }
    }

    private fun assertInvalid(message: String, init: XdTestEntity.() -> Unit) {
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                XdTestEntity.new {
                    badPassword = "12345"
                }.apply(init)
            }
        }
        assertThat(e.causes).hasSize(1)
        assertThat(e.causes.first().displayMessage).isEqualTo(message)
    }
}