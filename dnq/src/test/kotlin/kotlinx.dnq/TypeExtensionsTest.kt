package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import org.junit.Test
import java.util.*
import kotlin.properties.Delegates

class TypeExtensionsTest {

    @Test
    fun `extensions properties should be initialized in meta-model`() {
        XdModel.registerNode(EmptyUser)
        XdModel.registerNode(User)
        XdModel.withPlugins(
                SimpleModelPlugin(listOf(EmptyUser::name, EmptyUser::boss))
        )
        with(XdModel[EmptyUser]!!) {
            assertThat(simpleProperties).hasSize(1)
            assertThat(simpleProperties).containsKey("name")

            assertThat(linkProperties).hasSize(1)
            assertThat(linkProperties).containsKey("boss")
        }
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `model plugins should contain only delegated extension properties`() {
        XdModel.registerNode(EmptyUser)
        XdModel.registerNode(User)
        XdModel.withPlugins(
                SimpleModelPlugin(listOf(EmptyUser::notDelegatedField))
        )
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `model plugins should contain only extension delegates`() {
        XdModel.registerNode(EmptyUser)
        XdModel.registerNode(User)
        XdModel.withPlugins(
                SimpleModelPlugin(listOf(User::iq))
        )
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `model plugins should contain only database delegates`() {
        XdModel.registerNode(EmptyUser)
        XdModel.registerNode(User)
        XdModel.withPlugins(
                SimpleModelPlugin(listOf(EmptyUser::delegatedField))
        )
    }

}

var EmptyUser.name by xdRequiredStringProp()
var EmptyUser.boss by xdLink0_1(User)

val EmptyUser.notDelegatedField: String
    get() = UUID.randomUUID().toString()

val EmptyUser.delegatedField: String by Delegates.notNull<String>()

class EmptyUser(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<EmptyUser>()
}

class User(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<User>()

    var iq by xdRequiredIntProp()
}