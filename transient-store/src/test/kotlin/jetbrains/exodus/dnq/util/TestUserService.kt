/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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
