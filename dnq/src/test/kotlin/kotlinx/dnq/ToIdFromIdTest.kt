/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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

        transactional { txn ->
            val entity = txn.getEntity(txn.toEntityId(id))
            assertThat(entity).isNotNull()
        }
    }

    @Test
    fun `find by correct id`() {
        transactional { txn ->
            val entity = txn.getEntity(txn.toEntityId("0-0"))
            assertThat(entity).isNotNull()
        }
    }

    @Test
    fun `find by incorrect id`() {
        transactional { txn ->
            assertFailsWith<EntityRemovedInDatabaseException> {
                txn.getEntity(txn.toEntityId("0-1"))
            }
        }
    }
}
