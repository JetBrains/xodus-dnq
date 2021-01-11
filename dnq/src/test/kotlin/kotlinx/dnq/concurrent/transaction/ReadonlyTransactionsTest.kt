/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.concurrent.transaction


import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.query.first
import kotlinx.dnq.query.iterator
import org.junit.Before
import org.junit.Test
import kotlin.test.fail

class ReadonlyTransactionsTest : DBTest() {
    private val count = 10

    class Something(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Something>() {
            fun new(text: String) = new {
                this.sometext = text
            }
        }

        var sometext by xdStringProp()
        var i1 by xdIntProp()
    }

    @Before
    fun createSomethings() {
        transactional {
            (0 until count).forEach { i ->
                Something.new("something $i")
            }
        }
    }

    override fun registerEntityTypes() {
        XdModel.registerNode(Something)
    }

    @Test
    fun `WD-2049 Nested read-only transactions fail`() {
        transactional(readonly = true) {
            transactional(readonly = true) {
                assertQuery(Something.all()).hasSize(count)

            }
            val i = Something.all().iterator().asSequence().count()
            assertThat(i).isEqualTo(count)
            assertQuery(Something.all()).hasSize(count)
        }
    }

    @Test
    fun `WD-2050 Cannot abort read-only transaction if it was reverted`() {
        transactional(readonly = true) { txn ->
            txn.revert()
        }
    }

    @Test
    fun `WD-2051 Nested transactional block is still read only`() {
        transactional(readonly = true) {
            transactional {
                Something.all().first().i1 = 1729
            }
        }
    }

    @Test
    fun `WD-2054 Read access to an instance of persistent class upgrades transaction to R-W`() {
        transactional { txn ->
            txn.setUpgradeHook(Runnable { fail("Transaction was upgraded") })
            for (s in Something.all()) {
                assertThat(s.sometext).isNotNull()
            }
        }
    }
}
