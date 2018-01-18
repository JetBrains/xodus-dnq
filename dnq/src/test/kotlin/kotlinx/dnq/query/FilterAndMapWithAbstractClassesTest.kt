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
package kotlinx.dnq.query

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.DBTest
import org.junit.Before
import org.junit.Test

class FilterAndMapWithAbstractClassesTest : DBTest() {

    private lateinit var root1: RootGroup
    private lateinit var root2: RootGroup
    private lateinit var root3: RootGroup

    private lateinit var nested1: NestedGroup
    private lateinit var nested2: NestedGroup

    @Test
    fun `should be possible to filter using abstract class as query root`() {
        store.transactional {
            with(Group.filter { it.alias eq "root" }) {
                assertThat(size()).isEqualTo(2)
                assertThat(contains(root1))
                assertThat(contains(nested1))
            }
        }
    }

    @Test
    fun `should be possible to filter using abstract class as link in query`() {
        store.transactional {
            with(NestedGroup.filter { it.parentGroup eq root1 }) {
                assertThat(size()).isEqualTo(1)
                assertThat(contains(nested1)).isTrue()
            }
        }
    }

    @Test
    fun `should be possible to mapDistinct using abstract class as link`() {
        store.transactional {
            with(NestedGroup.all().mapDistinct { it.parentGroup }) {
                assertThat(size()).isEqualTo(2)
                assertThat(contains(root1)).isTrue()
                assertThat(contains(root2)).isTrue()
            }
        }
    }

    @Test
    fun `should be possible to flatMapDistinct using abstract class as link`() {
        store.transactional {
            with(Group.all().flatMapDistinct { it.nestedGroups }) {
                assertThat(size()).isEqualTo(2)
                assertThat(contains(nested1)).isTrue()
                assertThat(contains(nested2)).isTrue()
            }
        }
    }

    @Before
    fun fillDB() {
        root1 = store.transactional {
            RootGroup.new { name = "root group 1"; alias = "root" }
        }
        root2 = store.transactional {
            RootGroup.new { name = "root group 2" }
        }
        root3 = store.transactional {
            RootGroup.new { name = "root group 3" }
        }
        val admin = store.transactional { User.new { login = "anakin"; skill = 1 } }
        nested1 = store.transactional {
            NestedGroup.new { name = "a"; owner = admin; parentGroup = root1; alias = "root" }
        }
        nested2 = store.transactional {
            NestedGroup.new { name = "b"; owner = admin; parentGroup = root2 }
        }

    }
}