/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
import com.jetbrains.teamsys.dnq.database.EntityOperations
import jetbrains.exodus.entitystore.PersistentEntityId
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
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

            assertThat(EntityOperations.isRemoved(store.persistentStore.getEntity(PersistentEntityId(0, 100)))).isTrue()
        }

    }
}
