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
import com.google.common.truth.Truth.assertWithMessage
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.database.TransientStoreSessionListener
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.size
import kotlinx.dnq.query.toList
import org.junit.Test

/**
 * XD-1283: the metadata initialization applies every enum type's constants inside ONE transaction and
 * flushes once for the whole phase, instead of once per enum type (which was one persistent commit
 * each - 50 of the 100 transactions of a YouTrack test's initialization).
 *
 * These tests pin the two properties the change relies on: the `flush` parameter really controls the
 * flush, and initializing several enum types without intermediate flushes produces each constant
 * exactly once, both on a fresh database and when the constants already exist.
 */
class EnumPhaseFlushTest : DBTest() {

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
     * does - updates the existing entities instead of creating new ones, with or without the
     * per-type flush.
     */
    @Test
    fun `re-initializing enum values without a per-type flush creates no duplicates`() {
        transactional { txn ->
            FirstEnum.initEnumValues(txn, flush = false)
            SecondEnum.initEnumValues(txn, flush = false)
            txn.flush()

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

    /**
     * The `flush` parameter is what the phase-level batching turns off, so it has to be the only
     * thing that flushes: with `false` the session keeps its changes pending, with the default it
     * does not. This is the property that turns N per-type commits into one phase commit.
     */
    @Test
    fun `the flush parameter controls whether the session is flushed`() {
        transactional { txn: TransientStoreSession ->
            FirstEnum.all().toList().forEach { it.delete() }
            SecondEnum.all().toList().forEach { it.delete() }
            txn.flush()

            FirstEnum.initEnumValues(txn, flush = false)
            assertWithMessage("initEnumValues(flush = false) must leave the changes pending")
                .that(txn.hasChanges())
                .isTrue()

            SecondEnum.initEnumValues(txn)
            assertWithMessage("initEnumValues(flush = true) must flush both enum types' changes")
                .that(txn.hasChanges())
                .isFalse()
        }
    }

    /**
     * The point of the change is the NUMBER of persistent commits: the phase-level shape the
     * initialization now uses flushes once for any number of enum types, where the previous per-type
     * shape flushed once per type. Counted through the session listener, whose `flushed` fires per
     * flush that carried changes.
     */
    @Test
    fun `the phase-level shape flushes once for two enum types where the per-type shape flushes twice`() {
        assertThat(flushesWhileInitializingBothEnumTypes(perTypeFlush = true))
            .isEqualTo(2)
        assertThat(flushesWhileInitializingBothEnumTypes(perTypeFlush = false))
            .isEqualTo(1)
    }

    /**
     * Deletes the constants (so both shapes have something to create), then applies both enum types
     * either flushing per type or once for the phase, and returns how many flushes carried changes.
     */
    private fun flushesWhileInitializingBothEnumTypes(perTypeFlush: Boolean): Int {
        transactional { txn ->
            FirstEnum.all().toList().forEach { it.delete() }
            SecondEnum.all().toList().forEach { it.delete() }
            txn.flush()
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
                FirstEnum.initEnumValues(txn, flush = perTypeFlush)
                SecondEnum.initEnumValues(txn, flush = perTypeFlush)
                if (!perTypeFlush) {
                    txn.flush()
                }
            }
        } finally {
            store.removeListener(counter)
        }
        return flushes
    }
}
