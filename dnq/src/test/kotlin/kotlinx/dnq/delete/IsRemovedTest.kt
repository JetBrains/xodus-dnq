package kotlinx.dnq.delete


import com.google.common.truth.Truth.assertThat
import com.jetbrains.teamsys.dnq.database.EntityOperations
import jetbrains.exodus.entitystore.PersistentEntityId
import jetbrains.exodus.entitystore.PersistentEntityStore
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.transactional
import org.junit.Test

class IsRemovedTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(Foo)
    }

    @Test
    fun isRemoved() {
        val foo2 = store.transactional { txn ->
            val foo = Foo.new()
            assertThat(foo.isRemoved).isFalse()

            foo.delete()
            assertThat(foo.isRemoved).isTrue()

            txn.revert()

            // foo creation is also reverted
            assertThat(foo.isRemoved).isTrue()
            val foo2 = Foo.new()

            foo2.delete()
            assertThat(foo2.isRemoved).isTrue()

            txn.flush()
            assertThat(foo2.isRemoved).isTrue()

            foo2
        }

        val foo3 = store.transactional {
            assertThat(foo2.isRemoved).isTrue()
            Foo.new()
        }

        store.transactional { txn ->
            assertThat(foo3.isRemoved).isFalse()

            foo3.delete()
            assertThat(foo3.isRemoved).isTrue()

            txn.revert()
            assertThat(foo3.isRemoved).isFalse()

            foo3.delete()
            assertThat(foo3.isRemoved).isTrue()
            txn.flush()

            assertThat(foo3.isRemoved).isTrue()

            assertThat(EntityOperations.isRemoved((store.persistentStore as PersistentEntityStore).getEntity(PersistentEntityId(0, 100)))).isTrue()
        }

    }
}
