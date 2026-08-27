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
package jetbrains.exodus.query

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.OTestMixin
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XD-1292 / audit #9-B — `InMemoryEntityIterator.skip` (the iterator behind every in-memory query
 * result in `dnq-query`) repeats #9's defect verbatim: it returns `true` regardless of whether
 * anything is left. Contract: `skip(n)` returns the value of `hasNext()`.
 *
 * **A FRESH `iterator()` per assertion** — every row is a statement about the *initial* iterator
 * state, so a shared iterator would make later rows depend on how much earlier rows consumed.
 */
class InMemoryEntityIteratorSkipTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB()

    override val youTrackDb = orientDbRule

    @Test
    fun `in-memory iterator skip returns hasNext`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            val engine = QueryEngine(null, youTrackDb.store)
            val entities: List<Entity> = listOf(test.issue1, test.issue2, test.issue3)

            fun iter() = InMemoryEntityIterable(entities, tx, engine).iterator()

            // skipping to exact exhaustion: nothing is left
            assertFalse(iter().skip(3))
            // one element left
            assertTrue(iter().skip(2))
            // skipping past the end: nothing is left
            assertFalse(iter().skip(4))

            // negative n is a no-op: nothing is consumed, and the answer is hasNext()
            val nonEmpty = iter()
            assertTrue(nonEmpty.skip(-1))
            assertEquals(test.issue1.id, nonEmpty.next().id)

            // ... which on an EMPTY iterable is false, where it used to be an unconditional true
            fun emptyIter() = InMemoryEntityIterable(emptyList(), tx, engine).iterator()
            assertFalse(emptyIter().skip(-1))
            assertFalse(emptyIter().skip(0))
        }
    }
}
