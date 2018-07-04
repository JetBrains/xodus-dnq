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
package kotlinx.dnq.linkConstraints

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.database.exceptions.UserConstraintValidationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.first
import org.junit.Test
import kotlin.test.assertFailsWith

class AssociationConstraintsTest : DBTest() {
    class Issue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Issue>()

        var user: User? by xdLink0_1(User::issues)
    }

    class Role(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Role>() {
            fun new() = new {
                this.name = "rolename"
            }
        }

        var name by xdStringProp()
    }

    class User(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<User>()

        var profile by xdChild0_1(UserProfile::user)
        var role by xdLink1(Role, onDelete = OnDeletePolicy.CLEAR)
        val issues by xdLink0_N(Issue::user, onDelete = OnDeletePolicy.CLEAR)

        override fun destructor() {
            assertThat(role.name).isNotNull()
        }

        override fun beforeFlush() {
            if (this.profile == null) {
                throw ConstraintsValidationException(if (isRemoved)
                    UserConstraintValidationException("Profile can't be null!")
                else
                    UserConstraintValidationException("Profile can't be null!", entity as TransientEntity))
            }
        }
    }

    class UserProfile(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<UserProfile>()

        var user: User by xdParent(User::profile)
    }

    class User2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<User2>()

        var profile by xdLink0_1(UserProfile2, onTargetDelete = OnDeletePolicy.CLEAR)
        override fun beforeFlush() {
            System.out.println("==========before flush========");
            if (this.profile == null) {
                throw ConstraintsValidationException(if (isRemoved)
                    UserConstraintValidationException("My profile can't be empty!")
                else
                    UserConstraintValidationException("My profile can't be empty!", entity as TransientEntity))
            }
        }
    }

    class UserProfile2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<UserProfile2>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Issue, Role, User, UserProfile, User2, UserProfile2)
    }

    private fun prepare() = User.new {
        profile = UserProfile.new()
        role = Role.new()
        issues.add(Issue.new())
    }

    @Test
    fun `on delete children should be cascade deleted`() {
        val user = transactional { prepare() }
        transactional {
            assertQuery(User.all()).hasSize(1)
            assertQuery(UserProfile.all()).hasSize(1)
            assertQuery(Role.all()).hasSize(1)
            assertQuery(Issue.all()).hasSize(1)

            assertThat(Issue.all().first().user).isEqualTo(User.all().first())
            user.delete()
        }
        transactional {
            assertQuery(User.all()).isEmpty()
            assertQuery(UserProfile.all()).isEmpty()
            assertQuery(Role.all()).hasSize(1)
            assertQuery(Issue.all()).hasSize(1)

            assertThat(Issue.all().first().user).isNull()
        }
    }

    @Test
    fun `on delete parent should be cleared`() {
        val user = transactional { prepare() }
        transactional {
            UserProfile.all().first().delete()
            user.delete()
        }
        transactional {
            assertQuery(UserProfile.all()).isEmpty()
        }
    }

    @Test
    fun beforeFlushTriggerOnLinkChange() {
        transactional { prepare() }
        assertFailsWith<ConstraintsValidationException>("User entity should be marked as changed and flushed") {
            transactional {
                UserProfile.all().first().delete()
                // Before flush triggers should be called
            }
        }
    }

    @Test
    fun `on delete beforeFlush should be triggered`() {
        val profile = transactional {
            val profile = UserProfile2.new()
            User2.new {
                this.profile = profile
            }
            profile
        }
        assertFailsWith<ConstraintsValidationException>("User entity should be marked as changed and flushed") {
            transactional {
                println("==========before delete========")
                profile.delete()
                // Before flush triggers should be called in descendant
            }
        }
    }

    @Test
    fun removedThanAddedNPE() {
        val (user, issue0, issue1) = transactional {
            val issue0 = Issue.new()
            val issue1 = Issue.new()
            val user = User.new {
                this.role = Role.new()
                this.profile = UserProfile.new()
                this.issues.add(issue0)
                this.issues.add(issue1)
            }
            Triple(user, issue0, issue1)
        }
        transactional {
            user.issues.remove(issue0)
            user.issues.remove(issue1)
            user.issues.add(issue0)
            issue1.delete()
        }
    }
}
