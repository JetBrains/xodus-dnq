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