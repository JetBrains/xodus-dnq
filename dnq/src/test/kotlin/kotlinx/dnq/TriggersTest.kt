/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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

import com.google.common.truth.Truth
import jetbrains.exodus.entitystore.Entity
import org.junit.Before
import org.junit.Test

class TriggersTest : DBTest() {
    companion object {
        var beforeFlushInvoked = false
    }

    class Triggers(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Triggers>()

        override fun beforeFlush() {
            beforeFlushInvoked = true
        }
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(Triggers)
    }

    @Before
    fun initTriggerFlags() {
        beforeFlushInvoked = false
    }

    @Test
    fun before_flush() {
        store.transactional {
            Triggers.new()
        }

        Truth.assertThat(beforeFlushInvoked).isTrue()
    }
}
