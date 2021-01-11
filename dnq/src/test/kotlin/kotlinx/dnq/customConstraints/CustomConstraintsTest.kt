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
package kotlinx.dnq.customConstraints


import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.constraints.notBlank
import kotlinx.dnq.*
import kotlinx.dnq.simple.*
import org.joda.time.DateTime
import org.joda.time.Period
import org.junit.Test
import kotlin.test.assertFailsWith

internal const val EMAIL_MESSAGE = "is not an email"
internal const val FUTURE_MESSAGE = "Not in future"
internal const val PAST_MESSAGE = "Not in past"

class CustomConstraintsTest : DBTest() {

    open class Constrained(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Constrained>()

        var email by xdStringProp { email(message = EMAIL_MESSAGE) }
        var alpha by xdStringProp { alpha() }
        var numeric by xdStringProp { numeric() }
        var noSlashes by xdStringProp { containsNone("/") }
        var from4to7CharsLong by xdStringProp { length(min = 4, max = 7) }
        var nonBlank by xdStringProp { constraints.add(notBlank()) }
        var inFuture by xdDateTimeProp { future(message = FUTURE_MESSAGE) }
        var inPast by xdDateTimeProp { past(message = PAST_MESSAGE) }
        var from4to7 by xdIntProp { min(4); max(7) }
        var url by xdStringProp { url() }
    }

    class ConstrainedChild(entity: Entity) : Constrained(entity) {
        companion object : XdNaturalEntityType<ConstrainedChild>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Constrained, ConstrainedChild)
    }

    @Test
    fun email_ok() {
        assertViolatesNothing {
            email = "maxim.mazin@jetbrains.com"
        }
    }

    @Test
    fun email_ok_2() {
        assertViolatesNothing {
            email = "maxim.mazin@jet-brains.com"
        }
    }

    @Test
    fun email_bad_1() {
        assertViolates(EMAIL_MESSAGE) {
            email = "maxim..mazin@jetbrains.com"
        }
    }

    @Test
    fun email_bad_hierarchy() {
        store.transactional { txn ->
            ConstrainedChild.new().fill {
                email = "maxim..mazin@jetbrains.com"
            }
            violates(txn, EMAIL_MESSAGE)
        }
    }

    @Test
    fun email_bad_2() {
        assertViolates(EMAIL_MESSAGE) {
            email = "maxim.mazin"
        }
    }

    @Test
    fun alpha_ok() {
        assertViolatesNothing {
            alpha = "abc"
        }
    }

    @Test
    fun alpha_bad() {
        assertViolates("should contain only letters") {
            alpha = "ab1c"
        }
    }

    @Test
    fun numeric_ok() {
        assertViolatesNothing {
            numeric = "123"
        }
    }

    @Test
    fun numeric_bad() {
        assertViolates("should contain only digits") {
            numeric = "ab1c"
        }
    }

    @Test
    fun containsNone_ok() {
        assertViolatesNothing {
            noSlashes = "123"
        }
    }

    @Test
    fun containsNone_bad() {
        assertViolates("shouldn't contain characters '/'") {
            noSlashes = "ac/dc"
        }
    }

    @Test
    fun length_less() {
        assertViolates("should be from 4 to 7 characters long") {
            from4to7CharsLong = "abc"
        }
    }

    @Test
    fun length_more() {
        assertViolates("should be from 4 to 7 characters long") {
            from4to7CharsLong = "abcefghij"
        }
    }

    @Test
    fun nonBlank_bad() {
        assertViolates("shouldn't be blank") {
            nonBlank = "   \n    "
        }
    }

    @Test
    fun future_ok() {
        assertViolatesNothing {
            inFuture = DateTime.now() + Period.days(1)
        }
    }

    @Test
    fun future_bad() {
        assertViolates(FUTURE_MESSAGE) {
            inFuture = DateTime.now()
        }
    }

    @Test
    fun past_ok() {
        assertViolatesNothing {
            inPast = DateTime.now() - Period.days(1)
        }
    }

    @Test
    fun past_bad() {
        assertViolates(PAST_MESSAGE) {
            inPast = DateTime.now() + Period.days(1)
        }
    }

    @Test
    fun inRange_less() {
        assertViolates("should be at least 4") {
            from4to7 = 3
        }
    }

    @Test
    fun inRange_more() {
        assertViolates("should be at most 7") {
            from4to7 = 8
        }
    }

    @Test
    fun url_ok() {
        assertViolatesNothing {
            url = "http://www.jetbrains.com/youtrack"
        }
    }

    @Test
    fun url_bad() {
        assertViolates("is not a valid URL") {
            url = "http//www.jetbrains.com/youtrack"
        }
    }

    private fun create(init: Constrained.() -> Unit): Constrained {
        return Constrained.new().fill(init)
    }

    private fun Constrained.fill(init: Constrained.() -> Unit) = apply {
        nonBlank = "smth"
        from4to7CharsLong = "1234"
        from4to7 = 5
        init()
    }

    private fun assertViolatesNothing(init: Constrained.() -> Unit) {
        store.transactional {
            create(init)
        }
    }

    private fun assertViolates(displayMessages: String, init: Constrained.() -> Unit) {
        store.transactional { txn ->
            create(init)
            violates(txn, displayMessages)
        }
    }

    private fun violates(txn: TransientStoreSession, displayMessages: String) {
        val e = assertFailsWith<ConstraintsValidationException> {
            txn.flush()
        }
        assertThat(e.causes.map { it.displayMessage }).containsExactly(displayMessages)
        txn.revert()
    }
}
