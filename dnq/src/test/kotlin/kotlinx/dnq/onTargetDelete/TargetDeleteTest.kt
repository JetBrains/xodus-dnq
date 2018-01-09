/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package kotlinx.dnq.onTargetDelete

import com.google.common.truth.Truth
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.first
import kotlinx.dnq.query.isEmpty
import kotlinx.dnq.query.size
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals

class TargetDeleteTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(A1, A2, A3, B1, B2, B3, B4, C1, C2, D1, D2, E1, E2, F1, F2)
    }

    @Test
    fun simpleCascade0() {
        val a3 = transactional {
            A3.new().apply {
                A1(this)
            }
        }
        transactional {
            a3.delete()
        }
        transactional {
            assertTrue(A1.all().isEmpty)
            assertTrue(A3.all().isEmpty)
        }
    }

    @Ignore
    @Test
    fun simpleConcurrentCascade() {
        val a3 = transactional { A3.new() }
        transactional {
            a3.delete()
            transactional(isNew = true) {
                A1(a3)
            }
        }
        transactional {
            assertTrue(A1.all().isEmpty)
            assertTrue(A3.all().isEmpty)
        }
    }

    @Test
    fun testCascadeWithInheritance() {
        val a2 = transactional { A2.new() }
        transactional { A1(a2) }
        transactional { a2.delete() }
        transactional {
            assertEquals(0, A1.all().size())
            assertEquals(0, A3.all().size())
        }
    }

    @Test
    fun nestedCascade() {
        val b4 = transactional {
            B4.new { B1(B2(B3(this))) }
        }
        transactional { b4.delete() }
        transactional {
            assertEquals(0, B1.all().size())
            assertEquals(0, B2.all().size())
            assertEquals(0, B3.all().size())
            assertEquals(0, B4.all().size())
        }
    }

    @Test
    fun simpleClear() {
        val c1 = transactional {
            C1.new { C2(this) }
        }
        transactional { c1.delete() }
        transactional {
            assertEquals(0, C1.all().size())
            assertEquals(1, C2.all().size())
            assertEquals(null, C2.all().first().c1)
        }
    }

    @Test
    fun clearAndCascade() {
        val first = transactional {
            val parent = D1.new()
            D2("some")
            D2("first").apply {
                parent.d2.add(this)
                parent.d2.add(D2("second"))
            }
        }
        transactional { first.delete() }
        transactional {
            assertEquals(1, D1.all().size())
            assertEquals(2, D2.all().size())
        }
    }

    @Test
    fun updateAndDelete() {
        val (first, e2a, e2b) = transactional {
            E1.new().let {
                Triple(it, E2.new { it.e2 = this }, E2.new())
            }
        }
        transactional {
            first.e2 = e2b
            e2a.delete()
        }
        transactional {
            assertEquals(e2b, first.e2)
        }
    }

    @Test
    fun updateAndSetNull() {
        val (e1, e2) = transactional {
            val e2 = E2.new()
            val e1 = E1.new {
                this.e2 = e2
            }
            e1 to e2
        }
        transactional {
            e2.delete()
        }
        transactional {
            Truth.assertThat(e1.e2).isNull()
        }
    }

    @Test
    fun cascadeDelete() {
        val leaf = transactional {
            F1.new {
                val leaf = F2.new()
                f2.add(leaf)
                f2.add(F2.new())
                f2.add(F2.new())
            }
        }
        transactional { leaf.delete() }
        transactional {
            assertEquals(0, F1.all().size())
            assertEquals(0, F2.all().size())
        }
    }
}
