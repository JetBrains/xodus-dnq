package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.size
import kotlinx.dnq.singleton.XdSingletonEntityType
import org.junit.Test
import kotlin.test.assertEquals

class SingletonTest : DBTest() {

    class TheKing(override val entity: Entity): XdEntity() {
        companion object: XdSingletonEntityType<TheKing>() {

            override fun TheKing.initSingleton() {
                name = "Elvis"
            }
        }

        var name by xdRequiredStringProp()
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(TheKing)
    }

    @Test
    fun `singleton should be alone`() {
        val (first, second) = store.transactional {
            val first = TheKing.get()
            val second = store.transactional(isNew= true) {
                TheKing.get()
            }
            Pair(first, second)
        }

        store.transactional {
            assertEquals(first, second)
        }
    }

    @Test
    fun `number of singletons should always be one`() {
        store.transactional {
            assertEquals(1, TheKing.all().size())
        }
    }
}