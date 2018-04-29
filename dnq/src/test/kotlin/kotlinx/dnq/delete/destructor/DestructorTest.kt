package kotlinx.dnq.delete.destructor

import com.google.common.truth.Truth
import com.google.common.truth.Truth.*
import com.jetbrains.teamsys.dnq.database.EntityOperations
import jetbrains.exodus.entitystore.Entity
import junit.framework.Assert
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import org.junit.Test

class DestructorTest : DBTest() {
    open class SomePC(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<SomePC>() {
            var DESTRUCTOR_CALLED = false
        }

        override fun destructor() {
            DESTRUCTOR_CALLED = true
        }
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(SomePC)
    }

    @Test
    fun `different transactions`() {
        SomePC.DESTRUCTOR_CALLED = false
        val p = transactional { SomePC.new() }
        transactional {
            p.delete()
            assertThat(SomePC.DESTRUCTOR_CALLED).isTrue()
        }
    }

    @Test
    fun `same transaction`() {
        SomePC.DESTRUCTOR_CALLED = false
        transactional {
            val p = SomePC.new()
            p.delete()
        }
        assertThat(SomePC.DESTRUCTOR_CALLED).isTrue()
    }
}
