/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.first
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
