/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.database.exceptions.UniqueIndexViolationException
import jetbrains.exodus.entitystore.Entity
import org.junit.Test
import kotlin.test.assertFailsWith

class CompositeIndexTest : DBTest() {

    class Service(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Service>()

        val defaultRoles by xdLink0_N(DefaultRole::service)
    }

    abstract class BaseRole(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<BaseRole>()

        var key by xdRequiredStringProp()
        var name by xdRequiredStringProp()
    }

    class DefaultRole(entity: Entity) : BaseRole(entity) {
        companion object : XdNaturalEntityType<DefaultRole>() {
            override val compositeIndices = listOf(
                    listOf(DefaultRole::service, DefaultRole::key)
            )
        }

        var service: Service by xdLink1(Service)
    }

    class Role(entity: Entity) : BaseRole(entity) {
        companion object : XdNaturalEntityType<Role>() {
            override val compositeIndices = listOf(
                    listOf(Role::key),
                    listOf(Role::name)
            )
        }
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(Service, BaseRole, DefaultRole, Role)
    }

    @Test
    fun `creation of entities with not unique fields that are part of composite index should be possible`() {
        store.transactional {
            Service.new {
                defaultRoles.add(DefaultRole.new { key = "A"; name = "a" })
                defaultRoles.add(DefaultRole.new { key = "B"; name = "a" })
            }
            Service.new {
                defaultRoles.add(DefaultRole.new { key = "A"; name = "a" })
            }
        }
    }

    @Test
    fun `creation of entities with not unique composite index should fail`() {
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                Service.new {
                    defaultRoles.add(DefaultRole.new { key = "A"; name = "a" })
                    defaultRoles.add(DefaultRole.new { key = "A"; name = "b" })
                }
            }
        }
        assertThat(e.causes.count())
                .isEqualTo(1)
        assertThat(e.causes.single())
                .isInstanceOf(UniqueIndexViolationException::class.java)
    }

    @Test
    fun `definition of index by property of parent entity should be possible`() {
        store.transactional {
            Role.new { key = "A"; name = "a" }
            Role.new { key = "B"; name = "b" }
        }
    }

    @Test(expected = ConstraintsValidationException::class)
    fun `definition of index by property of parent entity should not be possible`() {
        store.transactional {
            Role.new { key = "A"; name = "a" }
            Role.new { key = "A"; name = "a" }
        }
    }
}
