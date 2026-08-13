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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.database.TransientStoreSessionListener
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.size
import kotlinx.dnq.query.toList
import kotlin.test.assertFailsWith
import org.junit.Test

/**
 * Tests that enum initialization stays inside the transaction owned by the caller.
 *
 * Enum constants are ordinary entities and must be created exactly once. Initialization of all enum
 * types, entity types, and singletons is committed together by the enclosing transaction; enum
 * initialization itself must not introduce an intermediate flush.
 */
class EnumInitializationTest : DBTest() {

    class FirstEnum(entity: Entity) : XdEnumEntity(entity) {
        companion object : XdEnumEntityType<FirstEnum>() {
            val A by enumField { title = "first-a" }
            val B by enumField { title = "first-b" }
        }

        var title by xdRequiredStringProp(unique = true)
    }

    class SecondEnum(entity: Entity) : XdEnumEntity(entity) {
        companion object : XdEnumEntityType<SecondEnum>() {
            val C by enumField { title = "second-c" }
            val D by enumField { title = "second-d" }
        }

        var title by xdRequiredStringProp(unique = true)
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(FirstEnum)
        XdModel.registerNode(SecondEnum)
    }

    /**
     * The initialization pass already ran both enum types (through `initMetaData`), so every constant
     * must exist exactly once - no duplicates from the missing per-type flushes.
     */
    @Test
    fun `every enum constant is created exactly once by the initialization pass`() {
        transactional {
            assertThat(FirstEnum.all().toList().map { it.title })
                .containsExactly("first-a", "first-b")
            assertThat(SecondEnum.all().toList().map { it.title })
                .containsExactly("second-c", "second-d")
            assertThat(FirstEnum.A).isNotEqualTo(FirstEnum.B)
        }
    }

    /**
     * Re-applying the constants over an already-initialized database - which is what every restart
     * does - updates the existing entities instead of creating duplicates.
     */
    @Test
    fun `re-initializing enum values creates no duplicates`() {
        transactional { txn ->
            FirstEnum.initEnumValues(txn)
            SecondEnum.initEnumValues(txn)

            assertThat(FirstEnum.all().size()).isEqualTo(2)
            assertThat(SecondEnum.all().size()).isEqualTo(2)
        }
        transactional {
            assertThat(FirstEnum.all().toList().map { it.title })
                .containsExactly("first-a", "first-b")
            assertThat(SecondEnum.all().toList().map { it.title })
                .containsExactly("second-c", "second-d")
        }
    }

    @Test
    fun `enum initialization leaves changes pending until the enclosing transaction commits`() {
        transactional { txn ->
            FirstEnum.all().toList().forEach { it.delete() }
            SecondEnum.all().toList().forEach { it.delete() }
            txn.flush()

            FirstEnum.initEnumValues(txn)
            SecondEnum.initEnumValues(txn)

            assertThat(txn.hasChanges()).isTrue()
            assertThat(FirstEnum.all().size()).isEqualTo(2)
            assertThat(SecondEnum.all().size()).isEqualTo(2)
        }
    }

    @Test
    fun `enum initialization rolls back with the enclosing transaction`() {
        transactional {
            FirstEnum.all().toList().forEach { it.delete() }
        }

        assertFailsWith<IllegalStateException> {
            transactional { txn ->
                FirstEnum.initEnumValues(txn)
                throw IllegalStateException("fail after enum initialization")
            }
        }

        transactional {
            assertThat(FirstEnum.all().size()).isEqualTo(0)
        }
    }

    @Test
    fun `enum initialization does not flush before the enclosing transaction completes`() {
        transactional { txn ->
            FirstEnum.all().toList().forEach { it.delete() }
            SecondEnum.all().toList().forEach { it.delete() }
        }

        var flushes = 0
        val counter = object : TransientStoreSessionListener {
            override fun flushed(
                session: TransientStoreSession,
                changedEntities: Set<TransientEntityChange>
            ) {
                flushes++
            }

            override fun beforeFlushBeforeConstraints(
                session: TransientStoreSession,
                changedEntities: Set<TransientEntityChange>
            ) = Unit

            override fun afterConstraintsFail(
                session: TransientStoreSession,
                exceptions: Set<DataIntegrityViolationException>
            ) = Unit
        }
        store.addListener(counter)
        try {
            transactional { txn ->
                FirstEnum.initEnumValues(txn)
                SecondEnum.initEnumValues(txn)
                assertThat(flushes).isEqualTo(0)
            }
        } finally {
            store.removeListener(counter)
        }

        // The only flush is the enclosing transaction's final commit.
        assertThat(flushes).isEqualTo(1)
    }
}
