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

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.util.getDBName
import org.junit.Test

class LinksTypeTest : DBTest() {

    class Root(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Root>()

        val leafs by xdLink0_N(Leaf)
        val leaf by xdLink0_1(Leaf)
    }

    open class PrimitiveLeaf(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PrimitiveLeaf>()
    }

    open class Leaf(entity: Entity) : PrimitiveLeaf(entity) {
        companion object : XdNaturalEntityType<Leaf>()
    }

    class SuperLeaf(entity: Entity) : Leaf(entity) {
        companion object : XdNaturalEntityType<SuperLeaf>()
    }

    class Team(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Team>()
    }

    private val team by lazy { Team.new { } }
    private val root by lazy { Root.new { } }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Root, Leaf, Team, PrimitiveLeaf, SuperLeaf)
    }

    @Test(expected = Exception::class)
    fun `one to many link type constraint`() {
        store.transactional {
            root.entity.addLink(Root::leafs.getDBName(), team.entity)
        }
    }

    @Test(expected = Exception::class)
    fun `one to many link type constraint 2`() {
        store.transactional {
            root.entity.addLink(Root::leafs.getDBName(), PrimitiveLeaf.new {  }.entity)
        }
    }

    @Test
    fun `one to many link type constraint is ok`() {
        store.transactional {
            root.entity.addLink(Root::leafs.getDBName(), SuperLeaf.new {}.entity)
            root.entity.addLink(Root::leafs.getDBName(), Leaf.new {}.entity)
        }
    }

    @Test(expected = Exception::class)
    fun `one to one link type constraint`() {
        store.transactional {
            (root.entity as TransientEntity).setToOne(Root::leaf.getDBName(), team.entity)
        }
    }

    @Test(expected = Exception::class)
    fun `one to one link type constraint 2`() {
        store.transactional {
            (root.entity as TransientEntity).setToOne(Root::leaf.getDBName(), PrimitiveLeaf.new {}.entity)
        }
    }

    @Test
    fun `one to one link type constraint is ok`() {
        store.transactional {
            (root.entity as TransientEntity).setToOne(Root::leaf.getDBName(), SuperLeaf.new {}.entity)
            (root.entity as TransientEntity).setToOne(Root::leaf.getDBName(), Leaf.new {}.entity)
        }
    }
}