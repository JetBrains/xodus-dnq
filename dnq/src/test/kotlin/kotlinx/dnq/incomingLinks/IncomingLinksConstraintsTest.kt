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
package kotlinx.dnq.incomingLinks

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.exceptions.CantRemoveEntityException
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy.FAIL_PER_ENTITY
import kotlinx.dnq.link.OnDeletePolicy.FAIL_PER_TYPE
import kotlinx.dnq.util.getOldValue
import org.junit.Test
import kotlin.test.assertFailsWith

class IncomingLinksConstraintsTest : DBTest() {


    class XdComment(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdComment>()

        var author: XdUser by xdLink1(XdUser::comments, onTargetDelete = FAIL_PER_TYPE { linkedEntities, hasMore ->
            "User is author of ${linkedEntities.size} comments${if (hasMore) " and even more..." else ""}"
        })
    }

    class XdIssue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdIssue>()

        var id by xdStringProp()
        var assignee by xdLink1(XdUser, onTargetDelete = FAIL_PER_ENTITY {
            "assignee of ${it.toXd<XdIssue>().id}"
        })
    }

    class XdUser(entity: Entity) : XdEntity(entity) {

        companion object : XdNaturalEntityType<XdUser>()

        var login by xdRequiredStringProp()

        val comments by xdLink0_N(XdComment::author)

        val displayName get() = getOldValue(XdUser::login) ?: login
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(XdIssue, XdComment, XdUser)
    }

    @Test
    fun deleteUserWhileIssueHasLinksDIE() {
        val user = transactional {
            XdUser.new {
                val user = this
                login = "looser"

                (1..11).forEach { index ->
                    XdIssue.new {
                        id = "ID-$index"
                        assignee = user
                    }
                }

                (1..4).forEach {
                    XdComment.new {
                        author = user
                    }
                }
            }
        }

        val ex = assertFailsWith<ConstraintsValidationException> {
            transactional {
                user.delete()
            }
        }

        val integrityViolationExceptions = ex.causes
        assertThat(integrityViolationExceptions).hasSize(1)
        assertThat(integrityViolationExceptions.first()).isInstanceOf(CantRemoveEntityException::class.java)
        assertThat(integrityViolationExceptions.first() as CantRemoveEntityException)
                .hasMessageThat()
                .isEqualTo("Could not delete looser, because it is referenced as: " +
                        "assignee of ID-1, assignee of ID-2, assignee of ID-3, assignee of ID-4, assignee of ID-5, " +
                        "assignee of ID-6, assignee of ID-7, assignee of ID-8, assignee of ID-9, assignee of ID-10, " +
                        "and more...; " +
                        "User is author of 4 comments; ")
    }

    @Test
    fun deleteIssueWithLinkToUserNoDIE() {
        val issue = transactional {
            val user = XdUser.new { login = "me" }

            XdIssue.new {
                assignee = user
            }
        }

        transactional {
            issue.delete()
        }
    }
}
