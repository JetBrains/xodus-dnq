package kotlinx.dnq

import kotlinx.dnq.util.getDelegateField
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.memberProperties

@RunWith(Parameterized::class)
class DelegateFieldTest(val clazz: KClass<Any>, val fieldsWithDelegates: List<Pair<KClass<*>, KProperty1<*, *>>>) {

    open class A() {
        open val f1 by lazy { 1 }
        val f4 by lazy { 2 }
        val f2: Int get() = 1
    }

    open class B() : A() {
        override val f1 by lazy { 2 }
        val f3 by lazy { 4 }
    }


    companion object {
        @Parameterized.Parameters
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return listOf<Array<Any?>>(
                    arrayOf(A::class,
                            listOf(
                                    A::class to A::f1,
                                    A::class to A::f4)),
                    arrayOf(B::class,
                            listOf(
                                    B::class to B::f1,
                                    B::class to B::f3,
                                    A::class to A::f4))
            )
        }
    }

    @Test
    fun `delegateField should be found`() {
        fieldsWithDelegates.forEach {
            @Suppress("UNCHECKED_CAST")
            val delegateField = clazz.java.getDelegateField(it.second as KProperty1<Any, *>)
            Assert.assertNotNull(delegateField)
            Assert.assertEquals(it.first.java, delegateField?.declaringClass)
        }
    }

    @Test
    fun `only delegateFields should be found detected`() {
        clazz.memberProperties.forEach { property ->
            val expectedToHaveDelegate = fieldsWithDelegates.any { it.second.name == property.name }
            val actuallyHasDelegate = clazz.java.getDelegateField(property) != null

            Assert.assertEquals("${property.name} is ${if (expectedToHaveDelegate) "" else "not"} expected to have delegate", expectedToHaveDelegate, actuallyHasDelegate)
        }
    }
}