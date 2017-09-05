/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

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
        assertThat(XdModel[entityType]).isNotNull()
    }

    @Test
    fun `Parent should be as expected`() {
        assertThat(XdModel[entityType]?.parentNode)
                .isEqualTo(parent?.let { XdModel[parent] })
    }

    @Test
    fun `Children should be as expected`() {
        assertThat(XdModel[entityType]?.children)
                .containsExactlyElementsIn(children.map { XdModel[it] })
    }

    @Test
    fun `For non-abstract entities constructor should be defined `() {
        with(assertThat(XdModel[entityType]?.entityConstructor)) {
            if (hasConstructor) {
                isNotNull()
            } else {
                isNull()
            }
        }
    }
}