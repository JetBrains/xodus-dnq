/**
 * Copyright 2006 - 2023 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.blob

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.query.ne
import kotlinx.dnq.query.query
import org.junit.Test

class BlobTest : DBTest() {

    class User(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<User>()

        var bio by xdBlobStringProp()
        var photo by xdBlobProp()
    }


    companion object {
        val sampleBlob get() = "photo".toByteArray().inputStream()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(User)
    }

    @Test
    fun text() {
        val user = transactional {
            User.new { bio = "born in 1900" }
        }
        transactional {
            assertThat(user.bio).isEqualTo("born in 1900")
        }
    }

    @Test
    fun blob() {
        val user = transactional {
            User.new { bio = "born in 1900" }
        }
        transactional {
            user.photo = sampleBlob
        }
        transactional {
            assertThat(user.photo?.readBytes()).isEqualTo(sampleBlob.readBytes())
        }
    }

    @Test
    fun blobSearch() {
        val user = transactional {
            User.new { bio = "born in 1900" }
        }

        transactional {
            assertQuery(User.query(User::bio ne null)).containsExactly(user)
        }
    }

    @Test
    fun blobReplay() {
        val user = transactional {
            transactional(isNew = true) { User.new() }
            User.new { this.photo = sampleBlob }
        }
        transactional {
            assertThat(user.photo?.readBytes()).isEqualTo(sampleBlob.readBytes())
        }
    }
}
