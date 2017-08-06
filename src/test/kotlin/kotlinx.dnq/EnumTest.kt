package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.enum.XdEnumEntityType
import kotlinx.dnq.query.toList
import org.junit.Test

class EnumTest : DBTest() {

    class MyEnum(entity: Entity) : XdEnumEntity(entity) {
        companion object : XdEnumEntityType<MyEnum>() {
            val A by enumField { title = "a" }
            val B by enumField { title = "b" }
            val C by enumField { title = "c" }
        }

        var title by xdRequiredStringProp(unique = true)
    }


    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(MyEnum)
    }

    @Test
    fun `all enum values should be initialized`() {
        store.transactional {
            assertThat(MyEnum.A).isNotNull()
            assertThat(MyEnum.B).isNotNull()
            assertThat(MyEnum.C).isNotNull()
        }
    }

    @Test
    fun `all query should return enum values`() {
        store.transactional {
            assertThat(MyEnum.all().toList())
                    .containsExactly(MyEnum.A, MyEnum.B, MyEnum.C)
        }
    }
}