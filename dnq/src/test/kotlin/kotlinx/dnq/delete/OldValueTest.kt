/**
 * Copyright 2006 - 2019 JetBrains s.r.o.
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
package kotlinx.dnq.delete


import com.google.common.truth.Truth.assertThat
import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import com.jetbrains.teamsys.dnq.database.reattachTransient
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.util.getDBName
import kotlinx.dnq.util.getOldValue
import org.junit.Test

class OldValueTest : DBTest() {
    private val name = "user"
    private val name1 = "user1"

    override fun registerEntityTypes() {
        XdModel.registerNodes(RUser, RRole)
    }

    @Test
    fun oldValueOnChange() {
        val (user, role1, role2) = store.transactional {
            val user = RUser.new(name)
            val role1 = RRole.new()
            val role2 = RRole.new()
            user.role = role1
            Triple(user, role1, role2)
        }
        store.transactional { txn ->
            assertThat(user.getOldValue(RUser::name)).isEqualTo(name)

            // In webr-dnq oldValue for links returns null if link was not changed
            assertThat(AssociationSemantics.getOldValue(user.entity.reattachTransient(), RUser::role.getDBName())).isNull()

            // But it's not the case for Xodus-DNQ API
            assertThat(user.getOldValue(RUser::role)).isEqualTo(role1)

            user.name = name1
            user.role = role2
            assertThat(user.getOldValue(RUser::name)).isEqualTo(name)
            assertThat(user.getOldValue(RUser::role)).isEqualTo(role1)
            txn.revert()

            assertThat(user.getOldValue(RUser::name)).isEqualTo(name)
            assertThat(AssociationSemantics.getOldValue(user.entity.reattachTransient(), RUser::role.getDBName())).isNull()
            assertThat(user.getOldValue(RUser::role)).isEqualTo(role1)
            user.name = name1
            user.role = role2
            txn.flush()

            assertThat(user.getOldValue(RUser::name)).isEqualTo(name1)
            assertThat(AssociationSemantics.getOldValue(user.entity.reattachTransient(), RUser::role.getDBName())).isNull()
            assertThat(user.getOldValue(RUser::role)).isEqualTo(role2)
        }
    }

    @Test
    fun oldValueOnDelete() {
        val user = store.transactional {
            RUser.new(name).apply {
                role = RRole.new()
            }
        }
        store.transactional { txn ->
            user.delete()
            assertThat(user.getOldValue(RUser::name)).isEqualTo(name)

            txn.revert()
            assertThat(user.getOldValue(RUser::name)).isEqualTo(name)
            assertThat(AssociationSemantics.getOldValue(user.entity.reattachTransient(), RUser::role.getDBName())).isNull()
            assertThat(user.getOldValue(RUser::role)).isNotNull()

            user.delete()
            txn.flush()
            assertThat(user.getOldValue(RUser::name)).isNull()
            assertThat(user.getOldValue(RUser::role)).isNull()
        }
    }
}
