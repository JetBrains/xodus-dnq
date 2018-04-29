package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import org.junit.Test

class EqualsTest : DBTest() {

    class A(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<A>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(A)
    }

    @Test
    fun equalsSymmetry() {
        transactional {
            val a1 = A.new()
            transactional(isNew = true) {
                val a2 = A.new()
                assertThat(a1).isNotEqualTo(a2)
                assertThat(a2).isNotEqualTo(a1)
            }
        }
    }

    @Test
    fun equalsToItself() {
        val a = transactional {
            A.new()
        }
        transactional {
            assertThat(a).isEqualTo(a)
        }
    }
}