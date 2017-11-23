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
import kotlinx.dnq.query.XdMutableQuery
import org.junit.Test
import kotlin.test.assertFailsWith


class AbstractRelationTest {
    abstract class XdBaseGroup(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdBaseGroup>()

        abstract val parent: XdBaseGroup?
        val subgroups: XdMutableQuery<XdBaseGroup> by xdChildren0_N(XdBaseGroup::parent)
    }

    class XdGroup(entity: Entity) : XdBaseGroup(entity) {
        companion object : XdNaturalEntityType<XdGroup>()

        override var parent by xdParent(XdBaseGroup::subgroups)
    }

    class XdAnyoneGroup(entity: Entity) : XdBaseGroup(entity) {
        companion object : XdNaturalEntityType<XdAnyoneGroup>()

        override val parent: XdBaseGroup? = null
    }

    @Test
    fun `should throw on using links with abstract opposite fields`() {
        val e = assertFailsWith<UnsupportedOperationException> {
            XdModel.registerNodes(XdGroup, XdAnyoneGroup)
        }
        assertThat(e.message).isEqualTo("Property XdBaseGroup#subgroups has abstract opposite field XdBaseGroup::parent")
    }
}