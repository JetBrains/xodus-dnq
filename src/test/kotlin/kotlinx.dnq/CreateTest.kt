package kotlinx.dnq

import com.google.common.truth.Truth
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.query.query
import org.junit.Test

class CreateTest : DBTest() {

    class AdminGroup(entity: Entity) : RootGroup(entity) {

        companion object : XdNaturalEntityType<AdminGroup>()

        override fun constructor() {
            super.constructor()
            name = "admin"
        }
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(AdminGroup)
    }

    @Test
    fun create() {
        val login = "mazine"

        store.transactional {
            User.new {
                this.login = login
                this.skill = 1
            }
        }

        store.transactional {
            Truth.assertThat(User.query(User::login eq login).firstOrNull())
                    .isNotNull()
        }
    }

    @Test
    fun `create with constructor`() {
        store.transactional {
            Truth.assertThat(AdminGroup.new().name).isEqualTo("admin")
            Truth.assertThat(AdminGroup.new {
                this.name = "mega admin"
            }.name).isEqualTo("mega admin")
        }
    }

}