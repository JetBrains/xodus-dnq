package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.toList
import kotlinx.dnq.singleton.XdSingletonEntityType
import org.junit.Test

class SingletonTest : DBTest() {

    class TheKing(override val entity: Entity) : XdEntity() {
        companion object : XdSingletonEntityType<TheKing>() {

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
            val second = store.transactional(isNew = true) {
                TheKing.get()
            }
            Pair(first, second)
        }

        store.transactional {
            assertThat(first).isEqualTo(second)
        }
    }

    @Test
    fun `number of singletons should always be one`() {
        store.transactional {
            assertThat(TheKing.all().toList())
                    .containsExactly(TheKing.get())
        }
    }
}