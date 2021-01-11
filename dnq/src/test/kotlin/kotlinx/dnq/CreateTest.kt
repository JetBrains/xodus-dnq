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
package kotlinx.dnq

import com.google.common.truth.Truth
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.query.query
import org.junit.Test

class CreateTest : DBTest() {

    class AdminGroup(entity: Entity) : RootGroup(entity) {

        companion object : XdNaturalEntityType<AdminGroup>()

        override fun constructor() {
            super.constructor()
            name = "admin"
        }
    }

    abstract class SecurityGroup(entity: Entity) : RootGroup(entity) {

        companion object : XdNaturalEntityType<SecurityGroup>()

        override fun constructor() {
            super.constructor()
            name = "security"
        }

    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(AdminGroup, SecurityGroup)
    }

    @Test
    fun create() {
        val login = "mazine"

        store.transactional {
            User.new {
                this.login = login
                this.skill = 1
            }
        }

        store.transactional {
            Truth.assertThat(User.query(User::login eq login).firstOrNull())
                    .isNotNull()
        }
    }

    @Test(expected = Exception::class)
    fun `creation of abstract type is not allowed`() {
        store.transactional {
            it.newEntity(SecurityGroup.entityType)
        }
    }

    @Test
    fun `create with constructor`() {
        store.transactional {
            Truth.assertThat(AdminGroup.new().name).isEqualTo("admin")
            Truth.assertThat(AdminGroup.new {
                this.name = "mega admin"
            }.name).isEqualTo("mega admin")
        }
    }

}