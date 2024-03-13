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

class EqualsTest : DBTest() {

    class A(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<A>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(A)
    }

    @Test
    fun equalsSymmetry() {
        transactional {
            val a1 = A.new()
            transactional(isNew = true) {
                val a2 = A.new()
                assertThat(a1).isNotEqualTo(a2)
                assertThat(a2).isNotEqualTo(a1)
            }
        }
    }

    @Test
    fun equalsToItself() {
        val a = transactional {
            A.new()
        }
        transactional {
            assertThat(a).isEqualTo(a)
        }
    }
}
