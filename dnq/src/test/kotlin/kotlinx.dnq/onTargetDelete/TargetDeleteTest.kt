package kotlinx.dnq.onTargetDelete

import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.isEmpty
import kotlinx.dnq.query.size
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals

class TargetDeleteTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(A1, A2, A3)
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

}
