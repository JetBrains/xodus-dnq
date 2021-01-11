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

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import kotlinx.dnq.query.first
import kotlinx.dnq.util.findById
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class ToIdFromIdTest : DBTest() {
    class XdUser(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdUser>("User")

        var login by xdStringProp()
        var password by xdStringProp()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(XdUser)
    }

    @Before
    fun createData() {
        transactional {
            XdUser.new { login = "user"; password = "user" }
        }
    }

    @Test
    fun testPersistentEntity() {
        val id = transactional {
            XdUser.all()
                    .first()
                    .entityId
                    .toString()
        }

        transactional {
            val user = XdUser.findById(id)
            assertThat(user).isNotNull()
            assertThat(user.xdId).isEqualTo(id)
        }
    }

    @Test
    fun `find by correct id`() {
        transactional {
            val user = XdUser.findById("0-0")
            assertThat(user).isNotNull()
        }
    }

    @Test
    fun `find by incorrect id`() {
        transactional {
            assertFailsWith<EntityRemovedInDatabaseException> {
                XdUser.findById("0-1")
            }
        }
    }

    @Test
    fun `find by incorrect id with non-existing entity type`() {
        transactional {
            assertFailsWith<EntityRemovedInDatabaseException> {
                XdUser.findById("42-42")
            }
        }
    }
}
