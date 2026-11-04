/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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
package kotlinx.dnq.util

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypesTest : DBTest() {
    abstract class Base(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Base>()
    }
    abstract class Middle(entity: Entity) : Base(entity) {
        companion object : XdNaturalEntityType<Middle>()
    }
    class Leaf(entity: Entity) : Middle(entity) {
        companion object : XdNaturalEntityType<Leaf>()
    }
    class Sibling(entity: Entity) : Base(entity) {
        companion object : XdNaturalEntityType<Sibling>()
    }
    class Unrelated(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Unrelated>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Base, Middle, Leaf, Sibling, Unrelated)
    }

    @Test
    fun `both public overloads preserve literal class membership`() {
        transactional {
            val leaf = Leaf.new()
            val sibling = Sibling.new()
            val unrelated = Unrelated.new()
            for ((entity, expected) in listOf(
                leaf to setOf(Base, Middle, Leaf),
                sibling to setOf(Base, Sibling),
                unrelated to setOf(Unrelated)
            )) {
                for (type in listOf(Base, Middle, Leaf, Sibling, Unrelated)) {
                    assertEquals(type in expected, entity.isInstanceOf(type), type.entityType)
                    assertEquals(type in expected, entity.isInstanceOf(type.entityType), type.entityType)
                }
                assertFalse(entity.isInstanceOf("Unknown"))
            }
        }
    }

    @Test
    fun `type helper preserves identity before unknown lookup and directional ancestry`() {
        val model = store.modelMetaData!!
        for (type in listOf(Leaf, Middle, Base)) {
            assertTrue(isTypeOf(model, Leaf.entityType, type.entityType))
        }
        assertFalse(isTypeOf(model, Base.entityType, Leaf.entityType))
        assertFalse(isTypeOf(model, Leaf.entityType, Sibling.entityType))
        assertTrue(isTypeOf(model, "Unknown", "Unknown"))
        assertFalse(isTypeOf(model, "Unknown", Base.entityType))
        assertFalse(isTypeOf(model, Leaf.entityType, "Unknown"))
    }
}
