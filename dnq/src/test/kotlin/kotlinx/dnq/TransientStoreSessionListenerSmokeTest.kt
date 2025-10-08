/**
 * Copyright 2006 - 2025 JetBrains s.r.o.
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

import jetbrains.exodus.database.EntityChangeType
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.database.TransientStoreSessionListener
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.filter
import kotlinx.dnq.query.first
import kotlinx.dnq.query.toList
import kotlin.test.Test
import kotlin.test.assertEquals

class TransientStoreSessionListenerSmokeTest : DBTest() {

    class SomeEntity(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<SomeEntity>()

        var name by xdRequiredStringProp()
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(SomeEntity)
    }

    @Test
    fun `listener should bring events about basic create-update-delete operations`() {

        val listener = Listener()
        store.addListener(listener)

        store.transactional {
            SomeEntity.new { name = "abc" }
            SomeEntity.new { name = "xyz" }
        }

        assertEquals(setOf("abc", "xyz"), listener.added)
        listener.clear()

        store.transactional {
            SomeEntity
                .filter { it.name eq "abc" }
                .first()
                .name = "123"
        }
        assertEquals(mapOf("abc" to "123"), listener.updated)
        listener.clear()

        store.transactional {
            SomeEntity
                .filter { it.name eq "xyz" }
                .first()
                .delete()
        }
        assertEquals(setOf("xyz"), listener.removed)

        store.transactional {
            assertEquals(
                listOf("123"),
                SomeEntity.all().toList().map { it.name }
            )
        }
    }

    class Listener : TransientStoreSessionListener {
        val added = mutableSetOf<String>()
        val removed = mutableSetOf<String>()
        val updated = mutableMapOf<String, String>()

        fun clear() {
            added.clear()
            removed.clear()
            updated.clear()
        }

        override fun flushed(
            session: TransientStoreSession,
            changedEntities: @JvmSuppressWildcards Set<TransientEntityChange>
        ) {
            for (change in changedEntities) {
                when (change.changeType) {
                    EntityChangeType.ADD -> added.add(change.transientEntity.getProperty("name") as String)
                    EntityChangeType.REMOVE -> removed.add(change.snapshotEntity.getProperty("name") as String)
                    EntityChangeType.UPDATE -> updated[change.snapshotEntity.getProperty("name") as String] =
                        change.transientEntity.getProperty("name") as String
                }
            }
        }

        override fun beforeFlushBeforeConstraints(
            session: TransientStoreSession,
            changedEntities: @JvmSuppressWildcards Set<TransientEntityChange>
        ) {
        }

        override fun afterConstraintsFail(
            session: TransientStoreSession,
            exceptions: @JvmSuppressWildcards Set<DataIntegrityViolationException>
        ) {
        }

    }
}