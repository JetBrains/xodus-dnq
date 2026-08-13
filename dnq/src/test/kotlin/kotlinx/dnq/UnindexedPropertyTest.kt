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
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.filter
import kotlinx.dnq.query.first
import kotlinx.dnq.query.query
import kotlinx.dnq.query.size
import kotlinx.dnq.query.toList
import kotlinx.dnq.store.container.StaticStoreContainer
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * A property may opt out of the automatic per-property index with `indexed = false`.
 *
 * Motivation: YouTrackDB indexes every simple property, and a B-tree index key may not exceed
 * `BTREE_MAX_KEY_SIZE` - 30% of the page size, i.e. ~2457 bytes with the default 8 KB page. Writing a longer
 * value into an indexed property fails with `TooBigIndexKeyException`, so an unbounded string had no way to
 * live in a plain property at all. Each test below has its indexed twin as a negative control, so the tests
 * fail if the opt-out silently stops taking effect.
 */
class UnindexedPropertyTest : DBTest() {

    class Doc(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Doc>()

        var indexedPayload by xdStringProp()
        var payload by xdStringProp(indexed = false)
        var requiredPayload by xdRequiredStringProp(indexed = false)
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(Doc)
    }

    /** Longer than any possible BTREE_MAX_KEY_SIZE with the default page size. */
    private val hugeValue = "x".repeat(10_000)

    private fun indexExists(name: String) =
        StaticStoreContainer.dbProvider!!.withSession { session -> session.schema.indexExists(name) }

    @Test
    fun `unindexed property gets no automatic index, indexed one does`() {
        assertThat(indexExists("Doc_indexedPayload")).isTrue()
        assertThat(indexExists("Doc_payload")).isFalse()
        assertThat(indexExists("Doc_requiredPayload")).isFalse()
    }

    @Test
    fun `unindexed property accepts a value larger than the index key limit`() {
        store.transactional {
            Doc.new { payload = hugeValue; requiredPayload = hugeValue }
        }

        store.transactional {
            val doc = Doc.all().first()
            assertThat(doc.payload).isEqualTo(hugeValue)
            assertThat(doc.requiredPayload).isEqualTo(hugeValue)
        }
    }

    @Test
    fun `indexed property still rejects a value larger than the index key limit`() {
        // Negative control: this is exactly what the opt-out exists to avoid, and it must keep failing.
        val e = assertFailsWith<Throwable> {
            store.transactional {
                Doc.new { requiredPayload = "short"; indexedPayload = hugeValue }
            }
        }
        val chain = generateSequence<Throwable>(e) { it.cause }
            .joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
        // e.g. TooBigIndexKeyException: Key size is more than allowed, operation was canceled.
        //      Current key size 10024, allowed 2457 [...] Component Name="Doc_indexedPayload"
        assertThat(chain).contains("TooBigIndexKeyException")
        assertThat(chain).contains("Doc_indexedPayload")
    }

    @Test
    fun `unindexed property is still usable as a query predicate`() {
        store.transactional {
            Doc.new { requiredPayload = "r"; payload = "alpha" }
            Doc.new { requiredPayload = "r"; payload = "beta" }
            Doc.new { requiredPayload = "r"; payload = hugeValue }
        }

        store.transactional {
            assertThat(Doc.query(Doc::payload eq "alpha").toList().map { it.payload })
                .containsExactly("alpha")
            assertThat(Doc.filter { it.payload eq "beta" }.size()).isEqualTo(1)
            // Querying by an oversized value is fine too - nothing probes an index.
            assertThat(Doc.query(Doc::payload eq hugeValue).size()).isEqualTo(1)
            assertThat(Doc.query(Doc::payload eq "gamma").size()).isEqualTo(0)
        }
    }
}
