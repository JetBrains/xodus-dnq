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
package kotlinx.dnq.delete

import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import org.junit.Test
import kotlin.test.assertNotNull

/**
 * Parent link must remain accessible in removedSyncBeforeConstraints
 * when delete() follows an intermediate flush() with pending changes in the
 * same transaction.
 */
class DeleteAfterIntermediateFlushTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(Team, Fellow)
    }

    @Test
    fun `parent link is accessible in removedSyncBeforeConstraints after intermediate flush`() {
        val fellow = store.transactional {
            val team = Team.new { name = "team" }
            Fellow.new { name = "fellow"; this.team = team }
        }

        var capturedParent: Team? = null
        store.changesMultiplexer!!.addListener(Fellow, object : XdEntityListener<Fellow> {
            override fun removedSyncBeforeConstraints(removed: Fellow) {
                capturedParent = removed.team
            }
        })

        store.transactional { txn ->
            fellow.name = "updated"
            txn.flush()
            fellow.delete()
        }

        assertNotNull(capturedParent)
    }
}
