package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.enum.XdEnumEntityType
import kotlinx.dnq.query.contains
import kotlinx.dnq.query.size
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnumTest: DBTest() {

    class MyEnum(entity: Entity): XdEnumEntity(entity) {
        companion object: XdEnumEntityType<MyEnum>() {
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
            assertNotNull(MyEnum.A)
            assertNotNull(MyEnum.B)
            assertNotNull(MyEnum.C)
        }
    }

    @Test
    fun `all query should return enum values`() {
        store.transactional {
            assertEquals(3, MyEnum.all().size())

            assertTrue(MyEnum.all().contains(MyEnum.A))
            assertTrue(MyEnum.all().contains(MyEnum.B))
            assertTrue(MyEnum.all().contains(MyEnum.C))
        }
    }
}