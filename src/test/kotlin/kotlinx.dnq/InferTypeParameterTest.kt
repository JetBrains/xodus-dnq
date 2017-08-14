package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.util.inferTypeParameters
import org.junit.Test
import java.lang.reflect.TypeVariable

class InferTypeParameterTest {

    open class Base<P : Any, S : Any?>

    open class A<AP : Any> : Base<AP, Int>()

    open class B : A<String>()

    class C : B()

    class D<O> : B()

    @Test
    fun `Class itself should deliver its own type variables`() {
        val actual = Base::class.java.inferTypeParameters(Base::class.java)

        assertThat(actual.filterIsInstance<TypeVariable<*>>().map { it.name })
                .containsExactly("P", "S")
                .inOrder()
    }

    @Test
    fun `Parameterized direct inheritor should deliver inlined parameters`() {
        val actual = A::class.java.inferTypeParameters(Base::class.java)

        assertThat(actual.map { it.toString() })
                .containsExactly("AP", "class ${Int::class.javaObjectType.name}")
                .inOrder()
    }

    @Test
    fun `Indirect inheritor that defines explicit type should deliver inlined parameters`() {
        val actual = B::class.java.inferTypeParameters(Base::class.java)

        assertThat(actual)
                .isEqualTo(arrayOf(String::class.java, Int::class.javaObjectType))
    }

    @Test
    fun `Indirect inheritor that defines no parameter should deliver inherited inlined parameters`() {
        val actual = C::class.java.inferTypeParameters(Base::class.java)

        assertThat(actual)
                .isEqualTo(arrayOf(String::class.java, Int::class.javaObjectType))
    }

    @Test
    fun `Parameters of the inheritor should not confuse`() {
        val actual = D::class.java.inferTypeParameters(Base::class.java)

        assertThat(actual)
                .isEqualTo(arrayOf(String::class.java, Int::class.javaObjectType))
    }

//    private fun TypeVariable(name: String) = object : CustomMatcher<Type>("type variable named $name") {
//        override fun matches(item: Any?): Boolean {
//            return (item as? TypeVariable<*>?)?.name == name
//        }
//    }
}