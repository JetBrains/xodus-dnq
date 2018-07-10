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

    class Leaf(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Leaf>()
    }

    class Team(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Team>()
    }


    private val team by lazy { Team.new { } }
    private val root by lazy { Root.new { } }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Root, Leaf, Team)
    }

    @Test//(expected = Exception::class)
    fun `one to many link type constraint`() {
        store.transactional {
            root.entity.addLink(Root::leafs.getDBName(), team.entity)
        }
    }

    @Test//(expected = Exception::class)
    fun `one to one link type constraint`() {
        store.transactional {
            (root.entity as TransientEntity).setToOne(Root::leaf.getDBName(), team.entity)
        }
    }
}