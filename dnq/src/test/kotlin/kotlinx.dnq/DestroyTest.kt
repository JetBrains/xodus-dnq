package kotlinx.dnq

import jetbrains.exodus.database.exceptions.CantRemoveEntityException
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import org.junit.Test
import kotlin.test.assertFailsWith

class DestroyTest : DBTest() {

    class Undestroyable(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<Undestroyable>()

        override fun destructor() {
            throw ConstraintsValidationException(CantRemoveEntityException(entity, "Undestroyable entity", "Undestroyable", emptyList()))
        }
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(Undestroyable)
    }

    @Test
    fun destroy() {
        val undestroyable = store.transactional {
            Undestroyable.new()
        }

        assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                undestroyable.delete()
            }
        }
    }
}