package jetbrains.exodus.dnq.util

import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity

class TestUserService {
    companion object {
        @JvmStatic
        fun findUser(store: TransientEntityStore, username: String, password: String): Entity? {
            val users = store.threadSession!!.getAll("User").filter {
                PrimitiveAssociationSemantics.get(it, "username", String::class.java, null) == username
                        && PrimitiveAssociationSemantics.get(it, "password", String::class.java, null) == password
            }
            return if (!users.isEmpty()) {
                users.first()
            } else null
        }
    }
}
