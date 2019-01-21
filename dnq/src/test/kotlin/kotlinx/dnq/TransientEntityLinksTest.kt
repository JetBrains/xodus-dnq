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
import jetbrains.exodus.entitystore.Entity
import org.junit.Test

/**
 * Date: 28.12.2006
 * Time: 12:56:41
 *
 * @author Vadim.Gurov
 */
class TransientEntityLinksTest : DBTest() {

    class XdUser(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdUser>("User")

        var login by xdStringProp()
        var password by xdStringProp()
    }

    class XdIssue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdIssue>("Issue")

        var reporter by xdLink0_1(XdUser)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(XdUser, XdIssue)
    }

    @Test
    fun testTransientGetLinks() {
        transactional {
            XdUser.new { login = "user"; password = "user" }
            XdUser.new { login = "user1"; password = "user1" }
        }

        transactional { txn ->
            val user = txn
                    .find(XdUser.entityType, XdUser::login.name, "user")
                    .firstOrNull()
                    ?.toXd<XdUser>()
            assertThat(user).isNotNull()

            val issue = XdIssue.new { reporter = user }
            assertThat(issue.reporter).isEqualTo(user)
        }

        transactional { txn ->
            val user1 = txn
                    .find(XdUser.entityType, XdUser::login.name, "user1")
                    .firstOrNull()
                    ?.toXd<XdUser>()
            assertThat(user1).isNotNull()

            val issue = txn.getAll("Issue")
                    .firstOrNull()
                    ?.toXd<XdIssue>()
            assertThat(issue).isNotNull()

            issue?.reporter = user1

            assertThat(issue?.reporter).isEqualTo(user1)
        }
    }

}
