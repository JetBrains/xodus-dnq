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
package kotlinx.dnq.delete


import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
import org.junit.Test

class SophisticatedTargetDeleteTest : DBTest() {

    class Issue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Issue>()

        val issueLinks by xdLink0_N(IssueLink, onDelete = CASCADE, onTargetDelete = CLEAR)
    }


    class IssueLink(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<IssueLink>() {
            fun new(source: Issue, target: Issue) = new {
                this.source = source
                this.target = target
                source.issueLinks.add(this)
                target.issueLinks.add(this)
            }
        }

        var source by xdLink1(Issue, onTargetDelete = CASCADE)
        var target by xdLink1(Issue, onTargetDelete = CASCADE)
    }

    class User(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<User>()
    }


    class UserData(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<UserData>() {
            fun new(user: User) = new {
                this.user = user
            }
        }

        var user by xdLink1(User, onTargetDelete = CASCADE)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Issue, IssueLink, User, UserData)
    }

    @Test
    fun targetDelete() {
        val a = transactional { Issue.new() }
        val b = transactional { Issue.new() }
        val x = transactional { IssueLink.new(a, Issue.new()) }
        transactional { IssueLink.new(a, b) }

        transactional {
            x.delete()
            b.delete()
        }
        transactional {
            //  if deletion of x and b went wrong, then deletion of a will fail, since A->y link is left, but y deleted
            a.delete()
        }
    }

    @Test
    fun targetCascadeDelete() {
        val a = transactional { User.new() }
        val b = transactional { User.new() }
        val x = transactional { UserData.new(a) }

        transactional {
            x.user = b
            a.delete()
        }
        transactional {
            assertQuery(UserData.all()).hasSize(1)
        }
    }
}
