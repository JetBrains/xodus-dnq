package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

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

        assertTrue { beforeFlushInvoked }
    }
}
