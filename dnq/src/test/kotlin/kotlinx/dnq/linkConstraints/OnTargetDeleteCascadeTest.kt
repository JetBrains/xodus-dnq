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
package kotlinx.dnq.linkConstraints

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.toList
import org.junit.Ignore
import org.junit.Test

class OnTargetDeleteCascadeTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(AExternalProfile, AGuestUser, AUser, B1, B2, B3, B4, FRoot, FLeaf)
    }


    class AExternalProfile(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<AExternalProfile>()

        var user by xdLink1(AUser, onTargetDelete = OnDeletePolicy.CASCADE)
    }

    open class AUser(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<AUser>()
    }

    class AGuestUser(entity: Entity) : AUser(entity) {
        companion object : XdNaturalEntityType<AGuestUser>()
    }

    @Test
    fun `onTargetDelete=CASCADE`() {
        val user = transactional {
            val user = AUser.new()
            AExternalProfile.new { this.user = user }
            user
        }
        transactional {
            user.delete()
        }
        transactional {
            assertThat(AExternalProfile.all().toList()).isEmpty()
            assertThat(AUser.all().toList()).isEmpty()
        }
    }

    @Ignore
    @Test
    fun `onTargetDelete=CASCADE with concurrency`() {
        val user = transactional { AUser.new() }
        transactional {
            user.delete()
            transactional(isNew = true) {
                AExternalProfile.new { this.user = user }
            }
        }
        transactional {
            assertThat(AExternalProfile.all().toList()).isEmpty()
            assertThat(AUser.all().toList()).isEmpty()
        }
    }

    @Test
    fun `onTargetDelete=CASCADE for a link of a superclass`() {
        val guestUser = transactional { AGuestUser.new() }
        transactional { AExternalProfile.new { user = guestUser } }
        transactional { guestUser.delete() }
        transactional {
            assertThat(AExternalProfile.all().toList()).isEmpty()
            assertThat(AUser.all().toList()).isEmpty()
        }
    }


    class B1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<B1>()

        var b2 by xdLink1(B2, onTargetDelete = OnDeletePolicy.CASCADE)
    }

    class B2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<B2>()

        val b3 by xdLink0_N(B3::b2, onTargetDelete = OnDeletePolicy.CASCADE, onDelete = OnDeletePolicy.CASCADE)
    }

    class B3(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<B3>()

        val b4 by xdLink1_N(B4::b3, onTargetDelete = OnDeletePolicy.CASCADE)
        val b2: B2? by xdLink0_1(B2::b3)
    }

    class B4(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<B4>()

        var b3: B3 by xdLink1(B3::b4)
    }

    @Test
    fun `nested onTargetDelete=CASCADE`() {
        val b4 = transactional {
            val b1 = B1.new()
            val b2 = B2.new()
            val b3 = B3.new()
            val b4 = B4.new()

            b1.b2 = b2
            b1.b2.b3.add(b3)
            b3.b4.add(b4)
            b4
        }
        transactional { b4.delete() }
        transactional {
            assertThat(B1.all().toList()).isEmpty()
            assertThat(B2.all().toList()).isEmpty()
            assertThat(B3.all().toList()).isEmpty()
            assertThat(B4.all().toList()).isEmpty()
        }
    }


    class FRoot(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FRoot>()

        val leaves by xdLink0_N(FLeaf::root, onTargetDelete = OnDeletePolicy.CASCADE, onDelete = OnDeletePolicy.CASCADE)
    }


    class FLeaf(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FLeaf>()

        var root: FRoot? by xdLink0_1(FRoot::leaves, onDelete = OnDeletePolicy.CLEAR)
    }

    @Test
    fun cascadeDelete() {
        val leaf = transactional {
            FRoot.new {
                val leaf = FLeaf.new()
                leaves.add(leaf)
                leaves.add(FLeaf.new())
                leaves.add(FLeaf.new())
            }
        }
        transactional { leaf.delete() }
        transactional {
            assertThat(FRoot.all().toList()).isEmpty()
            assertThat(FLeaf.all().toList()).isEmpty()
        }
    }
}
