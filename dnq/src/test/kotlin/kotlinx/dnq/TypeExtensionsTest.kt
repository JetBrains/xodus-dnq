/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
