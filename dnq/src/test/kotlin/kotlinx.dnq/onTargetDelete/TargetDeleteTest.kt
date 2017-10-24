package kotlinx.dnq.onTargetDelete

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
