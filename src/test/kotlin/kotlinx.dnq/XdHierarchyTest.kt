package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import org.hamcrest.collection.IsIterableContainingInAnyOrder
import org.hamcrest.core.IsEqual
import org.hamcrest.core.IsNot
import org.hamcrest.core.IsNull
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(Parameterized::class)
class XdHierarchyTest(
        val entityType: XdEntityType<*>,
        val parent: XdEntityType<*>?,
        val children: List<XdEntityType<*>>,
        val hasConstructor: Boolean) {


    abstract class XdA(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<XdA>()
    }

    open class XdB(entity: Entity) : XdA(entity) {
        companion object : XdNaturalEntityType<XdB>()
    }

    class XdC(entity: Entity) : XdB(entity) {
        companion object : XdNaturalEntityType<XdC>()
    }

    class XdD(entity: Entity) : XdB(entity) {
        companion object : XdNaturalEntityType<XdD>()
    }


    @Before
    fun setUp() {
        listOf(XdA, XdB, XdC, XdD).forEach {
            XdModel.registerNode(it)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return listOf<Array<Any?>>(
                    arrayOf(XdA, null, listOf(XdB), false),
                    arrayOf(XdB, XdA, listOf(XdC, XdD), true),
                    arrayOf(XdC, XdB, emptyList<XdEntityType<*>>(), true),
                    arrayOf(XdD, XdB, emptyList<XdEntityType<*>>(), true)
            )
        }
    }

    @Test
    fun `Entity type should be detected`() {
        assertNotNull(XdModel[entityType])
    }

    @Test
    fun `Parent should be as expected`() {
        assertEquals(parent?.let { XdModel[parent] }, XdModel[entityType]?.parentNode)
    }

    @Test
    fun `Children should be as expected`() {
        Assert.assertThat(XdModel[entityType]?.children, IsIterableContainingInAnyOrder(children.map {
            IsEqual(XdModel[it])
        }))
    }

    @Test
    fun `For non-abstract entities constructor should be defined `() {
        Assert.assertThat(XdModel[entityType]?.entityConstructor, if (hasConstructor) {
            IsNot(IsNull())
        } else {
            IsNull()
        })
    }
}