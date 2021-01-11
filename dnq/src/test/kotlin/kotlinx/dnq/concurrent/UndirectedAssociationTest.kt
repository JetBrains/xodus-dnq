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
package kotlinx.dnq.concurrent


import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.query.XdMutableQuery
import org.junit.Test

class UndirectedAssociationTest : DBTest() {

    class Citizen(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Citizen>()

        var passport by xdLink0_1(Passport::citizen)
        val foreignPassports by xdLink0_N(ForeignPassport::citizen)
        val homes by xdLink0_N(Home::citizens)
    }

    class ForeignPassport(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ForeignPassport>()

        var citizen: Citizen? by xdLink0_1(Citizen::foreignPassports)
    }

    class Home(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Home>()

        val citizens: XdMutableQuery<Citizen> by xdLink0_N(Citizen::homes)
    }

    class Passport(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Passport>()

        var citizen: Citizen? by xdLink0_1(Citizen::passport)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Citizen, ForeignPassport, Home, Passport)
    }

    @Test
    fun setOneToOne() {
        val (pavleg, passport1, passport2) = transactional { txn ->
            val passport1 = Passport.new()
            val pavleg = Citizen.new {
                this.passport = passport1
            }
            txn.flush()

            pavleg.passport = null

            val passport2 = store.runTranAsyncAndJoin {
                Passport.new {
                    pavleg.passport = this
                }
            }
            Triple(pavleg, passport1, passport2)
        }
        transactional {
            assertThat(pavleg.passport).isNull()
            assertThat(passport1.citizen).isNull()
            assertThat(passport2.citizen).isNull()
        }
    }

    @Test
    fun createOneToMany() {
        val pavleg = transactional { Citizen.new() }
        val vadeg = transactional { Citizen.new() }
        val slaveg = transactional { Citizen.new() }
        val passport = transactional { txn ->
            val passport = ForeignPassport.new()
            pavleg.foreignPassports.add(passport)
            txn.flush()

            vadeg.foreignPassports.add(passport)

            store.runTranAsyncAndJoin {
                slaveg.foreignPassports.add(passport)
            }
            passport
        }
        transactional {
            assertQuery(vadeg.foreignPassports).containsExactly(passport)
            assertQuery(pavleg.foreignPassports).isEmpty()
            assertQuery(slaveg.foreignPassports).isEmpty()
        }
    }

    @Test
    fun clearOneToMany() {
        val vadeg = transactional { Citizen.new() }
        transactional { txn ->
            vadeg.foreignPassports.add(ForeignPassport.new())
            vadeg.foreignPassports.add(ForeignPassport.new())
            txn.flush()

            vadeg.foreignPassports.clear()

            store.runTranAsyncAndJoin {
                vadeg.foreignPassports.add(ForeignPassport.new())
            }
        }
        transactional {
            assertQuery(vadeg.foreignPassports).isEmpty()
        }
    }

    @Test
    fun removeOneToMany() {
        val vadeg = transactional { Citizen.new() }
        val slaveg = transactional { Citizen.new() }

        val passport = transactional { txn ->
            val passport = ForeignPassport.new()
            vadeg.foreignPassports.add(passport)
            txn.flush()

            store.runTranAsyncAndJoin {
                slaveg.foreignPassports.add(passport)
            }
            vadeg.foreignPassports.remove(passport)

            passport
        }
        transactional {
            assertQuery(vadeg.foreignPassports).isEmpty()
            assertThat(passport.citizen).isEqualTo(slaveg)
            assertQuery(slaveg.foreignPassports).containsExactly(passport)
        }
    }

    @Test
    fun clearManyToMany() {
        val vadeg = transactional { Citizen.new() }

        transactional { txn ->
            vadeg.homes.add(Home.new())
            vadeg.homes.add(Home.new())
            txn.flush()

            vadeg.homes.clear()

            store.runTranAsyncAndJoin {
                vadeg.homes.add(Home.new())
            }
        }
        transactional {
            assertQuery(vadeg.homes).isEmpty()
        }
    }
}
