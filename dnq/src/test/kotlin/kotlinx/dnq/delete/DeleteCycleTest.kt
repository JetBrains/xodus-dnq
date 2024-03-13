/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
package kotlinx.dnq.delete

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.query.*
import org.junit.Test

class DeleteCycleTest : DBTest() {

    class Driver(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Driver>() {
            fun new(name: String, age: Int) = new {
                this.name = name;
                this.name2 = name;
                this.age = age;
                this.age2 = age;
            }
        }

        var name by xdStringProp()
        var name2 by xdStringProp()
        var age by xdIntProp()
        var age2 by xdIntProp()
    }


    class PC1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PC1>()

        var pc2 by xdLink1(PC2::pc1)
    }


    class PC2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PC2>()

        var pc1: PC1 by xdLink1(PC1::pc2)
    }


    class PC3(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PC3>() {
            fun new(name: String) = new {
                this.myName = name;
            }
        }

        var myName by xdRequiredStringProp()
    }


    class PCC1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PCC1>()

        var pcc2 by xdLink1(PCC2)
    }

    class PCC2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PCC2>()

        var pcc3 by xdLink1(PCC3)
    }

    class PCC3(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PCC3>()

        var pcc1 by xdLink1(PCC1)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Driver, PC1, PC2, PC3, PCC1, PCC2, PCC3)
    }

    @Test
    fun test1() {
        val (pc1, pc2) = transactional {
            val pc1 = PC1.new()
            val pc2 = PC2.new()
            pc1.pc2 = pc2
            Pair(pc1, pc2)
        }
        transactional {
            assertQuery(PC1.all()).hasSize(1)
            assertQuery(PC2.all()).hasSize(1)
        }
        transactional {
            pc1.delete()
            pc2.delete()
        }
        transactional {
            assertQuery(PC1.all()).isEmpty()
            assertQuery(PC2.all()).isEmpty()
        }
    }

    @Test
    fun test2() {
        val (pc1, pc2, pc3) = transactional {
            val pc1 = PCC1.new()
            val pc2 = PCC2.new()
            val pc3 = PCC3.new()
            pc1.pcc2 = pc2
            pc2.pcc3 = pc3
            pc3.pcc1 = pc1
            Triple(pc1, pc2, pc3)
        }
        transactional {
            assertQuery(PCC1.all()).hasSize(1)
            assertQuery(PCC2.all()).hasSize(1)
            assertQuery(PCC3.all()).hasSize(1)
        }
        transactional {
            pc1.delete()
            pc2.delete()
            pc3.delete()
        }
        transactional {
            assertQuery(PCC1.all()).isEmpty()
            assertQuery(PCC2.all()).isEmpty()
            assertQuery(PCC3.all()).isEmpty()
        }
    }

    @Test
    fun test3() {
        val num = 100
        transactional {
            for (i in 0 until num) {
                PC3.new("Jesus")
            }
        }
        transactional {
            assertQuery(PC3.all()).hasSize(num)
        }
        transactional { txn ->
            for (i in 0 until num) {
                PC3.all().first().delete()
                txn.flush()
            }
        }
        transactional {
            assertQuery(PC3.all()).isEmpty()
        }
    }

    @Test
    fun test3_inMemomoryDelete() {
        val num = 100
        transactional {
            for (i in 0 until num) {
                PC3.new("Jesus")
            }
        }
        transactional {
            assertQuery(PC3.all()).hasSize(num)
        }
        transactional {
            PC3.all().toList().forEach { it.delete() }
        }
        transactional {
            assertQuery(PC3.all()).isEmpty()
        }
    }

    @Test
    fun bulk_delete() {
        val num = 500
        transactional {
            for (i in 0 until num) {
                PC3.new("Jesus")
            }
        }
        transactional {
            assertQuery(PC3.all()).hasSize(num)
        }
        transactional { txn ->
            bulkDelete(txn, PC3.all())
        }
        transactional {
            assertThat(PCC3.all().isEmpty).isTrue()
        }
    }

    @Test
    fun createDeleteCreate() {
        // create
        transactional {
            for (i in 0..999) {
                Driver.new("driver$i", i)
            }
        }
        // delete
        transactional {
            for (i in 0..999) {
                Driver.query(Driver::age eq i).first().delete()
            }
        }
        transactional {
            assertQuery(Driver.all()).isEmpty()
        }

        // reopen env
        closeStore()
        openStore()

        // create
        transactional {
            for (i in 0..2999) {
                Driver.new("driver$i", i)
            }
        }
        // update
        transactional {
            for (i in 0..99) {
                val d = Driver.query(Driver::age eq i).first()
                println("Update $d")
                d.name = d.name?.toUpperCase()
            }
        }
    }

    private fun <XD : XdEntity> bulkDelete(txn: TransientStoreSession, seq: XdQuery<XD>) {
        var count = 0
        var it = seq.iterator()
        while (it.hasNext()) {
            it.next().delete()
            if (++count % 100 == 0) {
                txn.flush()
                it = seq.iterator()
            }
        }
        if (count % 100 != 0) {
            txn.flush()
        }
    }
}
