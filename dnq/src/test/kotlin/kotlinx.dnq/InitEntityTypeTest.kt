package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import org.junit.Test

class InitEntityTypeTest : DBTest() {

    class WithInitEntityType(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<WithInitEntityType>() {
            var instance: WithInitEntityType? = null

            override fun initEntityType() {
                instance = new()
            }
        }
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(WithInitEntityType)
    }

    @Test
    fun `initEntityType should be called during initialization`() {
        assertThat(WithInitEntityType.instance).isNotNull()
    }
}