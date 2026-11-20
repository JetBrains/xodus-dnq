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

/**
 * A property may opt out of the automatic per-property index with `indexed = false`.
 * YouTrackDB shortens oversized string keys in single-field indexes without changing the stored
 * value. Cover both the opt-out and exact equality lookups on the indexed property.
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
    fun `indexed oversized values keep their full contents and match exactly`() {
        val firstValue = hugeValue + "a"
        val secondValue = hugeValue + "b"
        store.transactional {
            Doc.new { requiredPayload = "short"; indexedPayload = firstValue }
            Doc.new { requiredPayload = "short"; indexedPayload = secondValue }
        }

        store.transactional {
            // Both values have the same shortened index key, but equality must use the full value.
            fun matchingValues(value: String) = Doc.query(Doc::indexedPayload eq value)
                .toList().map { it.indexedPayload }

            assertThat(matchingValues(firstValue)).containsExactly(firstValue)
            assertThat(matchingValues(secondValue)).containsExactly(secondValue)
            assertThat(matchingValues(hugeValue + "c")).isEmpty()
        }
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
