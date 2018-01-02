/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
import org.junit.Test

class InitEntityTypeTest : DBTest() {

    class WithInitEntityType(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<WithInitEntityType>() {
            var instance: WithInitEntityType? = null

            override fun initEntityType() {
                instance = new()
            }
        }
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(WithInitEntityType)
    }

    @Test
    fun `initEntityType should be called during initialization`() {
        assertThat(WithInitEntityType.instance).isNotNull()
    }
}