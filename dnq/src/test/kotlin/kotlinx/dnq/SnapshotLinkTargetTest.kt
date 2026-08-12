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

import jetbrains.exodus.database.EntityChangeType
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.database.TransientStoreSessionListener
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.util.getDBName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Counterpart to the writable-target cases in [TransientStoreSessionListenerTest]: navigating a
 * to-one link off a snapshot entity hands back a *writable* handle only when the current session
 * can actually back it.
 *
 * The case that needs its own model is a target removed in the same transaction whose link edge is
 * still in place at navigation time. With [OnDeletePolicy.CLEAR] (the policy the other test model
 * uses) the edge is stripped and the navigation already lands on a `YTDBVertexEntityRemoved`
 * snapshot. With [OnDeletePolicy.FAIL] the edge survives until the constraint check, so navigation
 * reaches a still-alive vertex that the changes tracker nevertheless considers removed. That handle
 * must stay read-only — otherwise writes against an entity that is on its way out appear to
 * succeed.
 */
class SnapshotLinkTargetTest : DBTest() {

    class FailPolicyTarget(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FailPolicyTarget>()

        var name by xdRequiredStringProp()
    }

    class FailPolicySource(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FailPolicySource>()

        var name by xdRequiredStringProp()
        var target by xdLink0_1(FailPolicyTarget, onTargetDelete = OnDeletePolicy.FAIL)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(FailPolicyTarget, FailPolicySource)
    }

    private class NavigatingListener : TransientStoreSessionListener {
        var navigated: Entity? = null
        var writeOutcome: String? = null
        var readBack: Comparable<*>? = null

        override fun beforeFlushBeforeConstraints(
            session: TransientStoreSession,
            changedEntities: Set<TransientEntityChange>
        ) {
            val change = changedEntities.singleOrNull {
                it.changeType == EntityChangeType.UPDATE &&
                        it.transientEntity.type == FailPolicySource.entityType
            } ?: return
            val target = change.snapshotEntity.getLink(FailPolicySource::target.getDBName()) ?: return
            navigated = target
            readBack = target.getProperty("name")
            writeOutcome = try {
                target.setProperty("name", "written-through-snapshot")
                "written"
            } catch (e: IllegalStateException) {
                "rejected: ${e.message}"
            }
        }

        override fun flushed(session: TransientStoreSession, changedEntities: Set<TransientEntityChange>) {}

        override fun afterConstraintsFail(
            session: TransientStoreSession,
            exceptions: Set<DataIntegrityViolationException>
        ) {
        }
    }

    @Test
    fun `snapshot navigation to a target removed in the same transaction stays read-only`() {
        val (target, source) = store.transactional {
            val target = FailPolicyTarget.new { name = "target" }
            val source = FailPolicySource.new { name = "source"; this.target = target }
            target to source
        }

        val listener = NavigatingListener()
        store.addListener(listener)
        try {
            store.transactional {
                // the source must be changed too, so that it shows up as an UPDATE in the
                // changes description and the listener gets a snapshot to navigate from
                source.name = "source-renamed"
                target.delete()
            }
            fail("the FAIL policy must reject deleting a target that is still linked")
        } catch (e: Exception) {
            // expected: constraint validation rejects the delete
        } finally {
            store.removeListener(listener)
        }

        val navigated = listener.navigated ?: fail("listener did not navigate to the target")
        assertEquals(target.entityId, navigated.id)
        assertTrue(
            (navigated as TransientEntity).isReadonly,
            "a target the changes tracker already knows as removed must not be handed out as writable"
        )
        assertEquals(
            "rejected: Entity is readonly",
            listener.writeOutcome,
            "writing through the snapshot to a removed target must be rejected, not silently accepted"
        )
        // the snapshot read still works — only mutation is refused
        assertEquals("target", listener.readBack)
    }
}
