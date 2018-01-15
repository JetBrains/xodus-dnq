package kotlinx.dnq.delete


import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.first
import kotlinx.dnq.transactional
import org.junit.Test
import kotlin.test.assertFailsWith

class RemovedEntityTest : DBTest() {
    override fun registerEntityTypes() {
        XdModel.registerNodes(Foo)
    }

    @Test
    fun `removed in one transaction throws exception in another`() {
        val ref1 = store.transactional {
            Foo.new { intField = 42 }
        }

        val ref2 = store.transactional {
            val ref2 = Foo.all().first(Foo::intField eq 42)
            ref2.delete()
            ref2
        }

        store.transactional {
            assertThat(ref1.isRemoved).isTrue()
            assertFailsWith<EntityRemovedInDatabaseException> {
                ref1.intField = 0
            }

            assertThat(ref2.isRemoved).isTrue()
            assertFailsWith<EntityRemovedInDatabaseException> {
                ref2.intField = 0
            }
        }
    }
}
