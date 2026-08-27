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

import com.jetbrains.teamsys.dnq.database.PersistentEntityIterableWrapper
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransactionImpl
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import kotlinx.dnq.query.coverage.Issue
import kotlinx.dnq.query.coverage.IssueTrackerDataset
import kotlinx.dnq.query.coverage.Project
import kotlinx.dnq.query.coverage.Sprint
import kotlinx.dnq.query.coverage.Tag
import kotlinx.dnq.query.coverage.Employee
import kotlinx.dnq.query.coverage.Manager
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * XD-1292 / audit #8 and D5 — operand normalisation at the two sites a `dnq`-module test can reach:
 * the raw `YTDBEntityIterableImpl` binary operators with a **wrapped EMPTY** operand, and
 * `PersistentEntityIterableWrapper.findLinks`, which was the one operator in that file not calling
 * `unwrap()` on its argument.
 *
 * `dnq` is the only module that can host these: `dnq-entity-store` and `dnq-query` have no
 * `dnq-transient-store` dependency, so `PersistentEntityIterableWrapper` is not on their test
 * classpath. Conversely `dnq` has no test-artifacts dependency on `dnq-entity-store`, so these tests
 * use `dnq`'s own model (`IssueTrackerModel`) and never `Issues.CLASS` / `OTestMixin`.
 */
class OperandNormalisationTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(Issue, Project, Sprint, Tag, Employee, Manager)
    }

    private lateinit var dataset: IssueTrackerDataset

    @Before
    fun setupDataset() {
        dataset = IssueTrackerDataset(store)
    }

    private fun <R> withLowLevelTx(block: (YTDBStoreTransactionImpl) -> R): R =
        store.persistentStore.computeInTransaction { tx -> block(tx as YTDBStoreTransactionImpl) }

    /**
     * The `=== EMPTY` fast path must test the **unwrapped** operand. A wrapper around EMPTY is not
     * identical to EMPTY, so today the guard misses, the cast succeeds (the wrapper *is* a
     * `YTDBEntityIterable`), `requirePolymorphicMatch` passes (the wrapper delegates `polymorphic`
     * through `unwrap()`, and EMPTY's is the interface default `true`), and then `rightIterable.query`
     * reaches `EMPTY.query`, which is `unsupported` → `UnsupportedOperationException("Should never be
     * called")`.
     *
     * **The receiver must be a RAW `YTDBEntityIterableImpl`.** At the DNQ level every receiver is
     * already a `PersistentEntityIterableWrapper`, whose own operators already do
     * `wrappedIterable.intersect(right.unwrap())` — so a wrapped EMPTY reaches the raw layer *bare*,
     * the existing guard fires, and a test written on `Issue.all().entityIterable` would pass today
     * for the wrong reason. Hence the low-level transaction below.
     */
    @Test
    fun `binary ops accept a wrapped EMPTY operand`() {
        val wrappedEmpty = transactional {
            PersistentEntityIterableWrapper(store, YTDBEntityIterable.EMPTY)
        }
        // precondition: the wrapper is NOT identical to EMPTY, but unwraps to it
        assertTrue(wrappedEmpty !== YTDBEntityIterable.EMPTY)
        assertSame(YTDBEntityIterable.EMPTY, wrappedEmpty.unwrap())

        withLowLevelTx { tx ->
            val raw = YTDBEntityIterable.where(Issue.entityType, tx.getStore(), GremlinBlock.All)
            // precondition: a raw impl, not a wrapper, and non-empty so `union` etc. are meaningful
            assertTrue(raw !is PersistentEntityIterableWrapper)
            assertTrue(raw.size() > 0)

            assertSame(YTDBEntityIterable.EMPTY, raw.intersect(wrappedEmpty))
            assertSame(YTDBEntityIterable.EMPTY, raw.intersectSavingOrder(wrappedEmpty))
            assertSame(raw, raw.union(wrappedEmpty))
            assertSame(raw, raw.minus(wrappedEmpty))
            assertSame(raw, raw.concat(wrappedEmpty))
            assertSame(YTDBEntityIterable.EMPTY, raw.findLinks(wrappedEmpty, "sprint"))
        }
    }

    /**
     * B1's guard half: `YTDBStoreTransactionImpl.findLinks` must also test the unwrapped operand.
     */
    @Test
    fun `transaction findLinks accepts a wrapped EMPTY operand`() {
        val wrappedEmpty = transactional {
            PersistentEntityIterableWrapper(store, YTDBEntityIterable.EMPTY)
        }

        withLowLevelTx { tx ->
            assertSame(
                YTDBEntityIterable.EMPTY,
                tx.findLinks(Issue.entityType, wrappedEmpty, "sprint", true)
            )
        }
    }

    /**
     * D5 — `PersistentEntityIterableWrapper.findLinks` was the only one of that file's six operand-
     * taking operators not calling `unwrap()` on its argument, so it handed a wrapper straight down to
     * the raw layer.
     *
     * Pinned with a dependency-free delegating spy; **mockk is deliberately not added to `dnq`**.
     *
     * The spy's shape is load-bearing, and two obvious alternatives are vacuous:
     *  - a spy whose `unwrap()` returns its **delegate** is discarded at construction — the wrapper's
     *    constructor stores `wrappedIterable.unwrap()`, so the field would become the delegate, the
     *    spy's `findLinks` would never run and `seen` would stay `null`, passing with the edit
     *    reverted. Hence `unwrap() = this`.
     *  - a spy that only watches the `query` getter cannot discriminate once #7 lands in the same
     *    commit, because the raw `findLinks` then unwraps the operand itself before reading `query` —
     *    green either way. Hence recording the operand *object* handed down.
     */
    @Test
    fun `wrapper findLinks unwraps its operand`() {
        withLowLevelTx { tx ->
            val raw = YTDBEntityIterable.where(Issue.entityType, tx.getStore(), GremlinBlock.All)
            val rawOperand = YTDBEntityIterable.where(Sprint.entityType, tx.getStore(), GremlinBlock.All)

            val spy = RecordingIterable(raw)
            val wrapper = PersistentEntityIterableWrapper(store, spy)
            // precondition: the spy survived construction (unwrap() returns itself)
            assertSame(spy, wrapper.unwrap())

            val wrappedOperand = PersistentEntityIterableWrapper(store, rawOperand)
            wrapper.findLinks(wrappedOperand, "sprint")

            val seen = assertNotNull(spy.seen, "the spy's findLinks was never reached")
            assertTrue(
                seen !is PersistentEntityIterableWrapper,
                "the wrapper handed its operand down still wrapped: ${seen.javaClass.simpleName}"
            )
            assertSame(wrappedOperand.unwrap(), seen)
        }
    }

    /**
     * A delegating spy over a real [YTDBEntityIterable] that records the operand it is handed.
     *
     * `unwrap()` returns **this** on purpose — see the KDoc of the test that uses it.
     */
    private class RecordingIterable(
        private val delegate: YTDBEntityIterable
    ) : YTDBEntityIterable by delegate {

        var seen: EntityIterable? = null

        override fun unwrap(): EntityIterable = this

        override fun findLinks(entities: EntityIterable, linkName: String): EntityIterable {
            seen = entities
            return delegate.findLinks(entities, linkName)
        }
    }

    /** Guards the fixture the other rows depend on. */
    @Test
    fun `dataset has issues and sprints`() {
        withLowLevelTx { tx ->
            assertTrue(YTDBEntityIterable.where(Issue.entityType, tx.getStore(), GremlinBlock.All).size() > 0)
            assertEquals(true, YTDBEntityIterable.where(Sprint.entityType, tx.getStore(), GremlinBlock.All).size() > 0)
        }
    }
}
