package kotlinx.dnq

import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.database.exceptions.UniqueIndexViolationException
import jetbrains.exodus.entitystore.Entity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompositeIndexTest : DBTest() {

    class Service(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<Service>()

        val defaultRoles by xdLink0_N(DefaultRole::service)
    }

    abstract class BaseRole : XdEntity() {
        companion object : XdNaturalEntityType<BaseRole>()

        var key by xdRequiredStringProp()
        var name by xdRequiredStringProp()
    }

    class DefaultRole(override val entity: Entity) : BaseRole() {
        companion object : XdNaturalEntityType<DefaultRole>() {
            override val compositeIndices = listOf(
                    listOf(DefaultRole::service, DefaultRole::key)
            )
        }

        var service: Service by xdLink1(Service)
    }

    class Role(override val entity: Entity) : BaseRole() {
        companion object : XdNaturalEntityType<Role>() {
            override val compositeIndices = listOf(
                    listOf(Role::key),
                    listOf(Role::name)
            )
        }
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(Service)
        XdModel.registerNode(BaseRole)
        XdModel.registerNode(DefaultRole)
        XdModel.registerNode(Role)
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
        assertEquals(1, e.causes.count())
        assertTrue(e.causes.single() is UniqueIndexViolationException)
    }

    @Test
    fun `definition of index by property of parent entity should be possible`() {
        store.transactional {
            Role.new { key = "A"; name = "a" }
            Role.new { key = "B"; name = "b" }
        }
    }
}