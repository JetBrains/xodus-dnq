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
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
import kotlinx.dnq.query.asSequence
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.query
import kotlinx.dnq.util.getOldValue
import kotlinx.dnq.util.isDefined
import org.junit.Test

class DestructorTest : DBTest() {
    class SomePC(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<SomePC>() {
            var DESTRUCTOR_CALLED = false
        }

        override fun destructor() {
            DESTRUCTOR_CALLED = true
        }
    }

    class C1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<C1>()

        override fun destructor() {
            C2.query(C2::c1 eq this)
                    .asSequence()
                    .forEach { it.c1 = null }
            System.out.println("destructor finished!");
        }
    }

    class C2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<C2>() {
            fun new(c1: C1) = new {
                this.c1 = c1
            }
        }

        var c1 by xdLink0_1(C1)
    }

    class CustomFieldClass(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CustomFieldClass>()

        val instances by xdLink0_N(CustomFieldInstance::clazz, onDelete = CASCADE, onTargetDelete = CLEAR)
    }

    class CustomFieldInstance(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CustomFieldInstance>()

        var clazz: CustomFieldClass by xdLink1(CustomFieldClass::instances, onDelete = CLEAR)

        override fun destructor() {
            assertThat(isDefined(CustomFieldInstance::clazz)).isTrue()
        }
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(SomePC, C1, C2, CustomFieldClass, CustomFieldInstance)
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

    @Test
    fun `manual onDelete constraint`() {
        val (c1, c2) = transactional {
            val c1 = C1.new()
            val c2 = C2.new(c1)
            Pair(c1, c2)
        }
        transactional {
            c1.delete()
            assertThat(c2.c1).isNull()
            assertThat(c2.getOldValue(C2::c1)).isEqualTo(c1)
        }
    }

    @Test
    fun `on delete constraints should be executed after desctructor`() {
        val (clazz, instance) = transactional {
            val clazz = CustomFieldClass.new()
            val instance = CustomFieldInstance.new()
            clazz.instances.add(instance)
            assertThat(instance.clazz).isEqualTo(clazz)
            assertQuery(clazz.instances).containsExactly(instance)
            Pair(clazz, instance)
        }
        transactional {
            assertThat(instance.clazz).isEqualTo(clazz)
            assertQuery(clazz.instances).containsExactly(instance)
        }
        transactional {
            clazz.delete()
        }
    }
}
