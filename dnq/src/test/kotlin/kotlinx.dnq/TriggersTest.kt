package kotlinx.dnq

import com.google.common.truth.Truth
import jetbrains.exodus.entitystore.Entity
import org.junit.Before
import org.junit.Test

class TriggersTest : DBTest() {
    companion object {
        var beforeFlushInvoked = false
    }

    class Triggers(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<Triggers>()

        override fun beforeFlush() {
            beforeFlushInvoked = true
        }
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(Triggers)
    }

    @Before
    fun initTriggerFlags() {
        beforeFlushInvoked = false
    }

    @Test
    fun before_flush() {
        store.transactional {
            Triggers.new()
        }

        Truth.assertThat(beforeFlushInvoked).isTrue()
    }
}
