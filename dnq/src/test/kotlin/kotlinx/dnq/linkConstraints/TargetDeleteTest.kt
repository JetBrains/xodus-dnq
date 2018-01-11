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
package kotlinx.dnq.linkConstraints

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.first
import kotlinx.dnq.query.toList
import org.junit.Ignore
import org.junit.Test

class TargetDeleteTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(A1, A2, A3, B1, B2, B3, B4, C1, C2, D1, D2, E1, E2, F1, F2)
    }

    @Test
    fun simpleCascade0() {
        val a3 = transactional {
            A3.new().also { a3 ->
                A1.new { l = a3 }
            }
        }
        transactional {
            a3.delete()
        }
        transactional {
            assertThat(A1.all().toList()).isEmpty()
            assertThat(A3.all().toList()).isEmpty()
        }
    }

    @Ignore
    @Test
    fun simpleConcurrentCascade() {
        val a3 = transactional { A3.new() }
        transactional {
            a3.delete()
            transactional(isNew = true) {
                A1.new { l = a3 }
            }
        }
        transactional {
            assertThat(A1.all().toList()).isEmpty()
            assertThat(A3.all().toList()).isEmpty()
        }
    }

    @Test
    fun testCascadeWithInheritance() {
        val a2 = transactional { A2.new() }
        transactional { A1.new { l = a2 } }
        transactional { a2.delete() }
        transactional {
            assertThat(A1.all().toList()).isEmpty()
            assertThat(A3.all().toList()).isEmpty()
        }
    }

    @Test
    fun nestedCascade() {
        val b4 = transactional {
            B4.new { B1(B2(B3(this))) }
        }
        transactional { b4.delete() }
        transactional {
            assertThat(B1.all().toList()).isEmpty()
            assertThat(B2.all().toList()).isEmpty()
            assertThat(B3.all().toList()).isEmpty()
            assertThat(B4.all().toList()).isEmpty()
        }
    }

    @Test
    fun simpleClear() {
        val c1 = transactional {
            C1.new { C2(this) }
        }
        transactional { c1.delete() }
        transactional {
            assertThat(C1.all().toList()).isEmpty()
            assertThat(C2.all().toList()).hasSize(1)
            assertThat(C2.all().first().c1).isNull()
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
            assertThat(D1.all().toList()).hasSize(1)
            assertThat(D2.all().toList()).hasSize(2)
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
            assertThat(first.e2).isEqualTo(e2b)
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
            assertThat(e1.e2).isNull()
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
            assertThat(F1.all().toList()).isEmpty()
            assertThat(F2.all().toList()).isEmpty()
        }
    }
}
