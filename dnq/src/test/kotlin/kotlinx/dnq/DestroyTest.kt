/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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

import jetbrains.exodus.database.exceptions.CantRemoveEntityException
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import org.junit.Test
import kotlin.test.assertFailsWith

class DestroyTest : DBTest() {

    class Undestroyable(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Undestroyable>()

        override fun destructor() {
            throw ConstraintsValidationException(CantRemoveEntityException(entity, "Undestroyable entity", "Undestroyable", emptyList()))
        }
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(Undestroyable)
    }

    @Test
    fun destroy() {
        val undestroyable = store.transactional {
            Undestroyable.new()
        }

        assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                undestroyable.delete()
            }
        }
    }
}