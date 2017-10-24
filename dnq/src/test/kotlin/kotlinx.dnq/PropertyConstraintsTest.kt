package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.simple.*
import org.junit.Test
import kotlin.test.assertFailsWith

class PropertyConstraintsTest : DBTest() {

    class XdTestEntity(override val entity: Entity) : XdEntity() {
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