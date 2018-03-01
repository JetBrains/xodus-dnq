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
package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import org.junit.Test
import kotlin.system.measureTimeMillis

class XdPerformanceTest : DBTest() {

    class XdUser(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdUser>()

        var f by xdStringProp()
        var o by xdIntProp()
        var r by xdDateTimeProp()
        var k by xdDateTimeProp()

        var y by xdStringProp()
        var o_ by xdIntProp()
        var u by xdDateTimeProp()

        var lead: XdUser? by xdLink0_1(XdUser::team)
        val team by xdLink0_N(XdUser::lead)
    }

    override fun registerEntityTypes() {
        XdModel.registerNode(XdUser)
    }

    @Test
    fun `estimate toXd`() {
        transactional {
            (1..1000).forEach {
                XdUser.new()
            }
        }

        val millis = transactional {
            val users = XdUser.all().entityIterable.toList()
            measureTimeMillis {
                for (i in 1..100_000) {
                    for (entity in users) {
                        entity.toXd<XdUser>()
                    }
                }
            }
        }
        println(millis)
    }
}