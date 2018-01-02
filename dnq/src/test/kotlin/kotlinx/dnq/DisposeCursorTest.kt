/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.asSequence
import kotlinx.dnq.query.isEmpty
import org.junit.Before
import org.junit.Test

/**
 * Date: 14.12.2006
 * Time: 14:26:16
 *
 * @author Vadim.Gurov
 */
class DisposeCursorTest : DBTest() {
    class XdUser(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdUser>("User")

        var login by xdStringProp()
        var password by xdStringProp()
    }

    class XdIssue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdIssue>("Issue")

        var summary by xdStringProp()
        var reporter by xdLink0_1(XdUser)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(XdUser, XdIssue)
    }

    @Before
    fun createUsers() {
        transactional {
            if (XdUser.all().isEmpty) {
                XdIssue.new {
                    reporter = XdUser.new {
                        login = "vadim"
                        password = "vadim"
                    }
                    summary = "test issue"
                }
            }
        }
    }


    @Test
    fun testDisposeCursor() {
        transactional {
            val user = XdUser.all()
                    .asSequence()
                    .firstOrNull { it.login == "vadim" && it.password == "vadim" }

            assertThat(user).isNotNull()
        }
    }

    @Test
    fun testDisposeOnLastElement() {
        transactional { txn ->
            val users = store.queryEngine.intersect(
                    txn.find("User", "login", "1"),
                    txn.find("User", "password", "1")
            )
            assertThat(users).isEmpty()
        }
    }


}
