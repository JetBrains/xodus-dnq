package kotlinx.dnq.query

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.NodeBase
import kotlinx.dnq.*
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.util.*

class IsInQueryTest : DBTest() {


    class Mage(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<Mage>()

        var charm by xdRequiredStringProp()
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(Mage)
    }

    @Ignore("long running test")
    @Test
    fun comparePerformance() {
        val magesCount = 200_000
        val searchesCount = 10_000
        val iterationsCount = 20
        val randomCharms = List(magesCount) { UUID.randomUUID().toString() }
        store.transactional {
            randomCharms.forEach { Mage.new { charm = it } }
        }

        println("Nodes count\t\tExplicit EQ and OR\t\tNone")
        (2..iterationsCount).forEach {
            val d1 = measure(randomCharms, searchesCount, it) { charms ->
                val firstCharm = charms.first()
                val seedNode = Mage::charm eq firstCharm
                val node = charms.drop(1).fold(seedNode) { tree: NodeBase, charm: String -> tree or (Mage::charm eq charm) }
                Mage.query(node)
            }

            val d2 = measure(randomCharms, searchesCount, it) { charms ->
                Mage.query(Mage::charm inValues charms)
            }

            println("%1$11s\t\t%2$18s\t\t%3$4s".format(it, d1, d2))
        }
    }

    @Test
    fun `should search in values`() {
        val expectedCharms = List(10) { UUID.randomUUID().toString() }
        store.transactional {
            expectedCharms.forEach { Mage.new { charm = it } }
        }

        store.transactional {
            expectedCharms.forEach {
                assertThat(Mage.query(Mage::charm.containsIn(it, UUID.randomUUID().toString())).size(), equalTo(1))
                assertThat(Mage.query(Mage::charm.containsIn(UUID.randomUUID().toString(), it)).size(), equalTo(1))
            }

            assertThat(Mage.query(Mage::charm inValues expectedCharms.drop(2).take(4)).size(), equalTo(4))

            assertTrue(Mage.query(Mage::charm inValues List(2) { UUID.randomUUID().toString() }).isEmpty)
        }
    }

    @Test
    fun `should search in entities`() {
        store.transactional {
            val users = List(100) { User.new {
                login = UUID.randomUUID().toString()
                skill = 1
            } }

            val (bossA, bossB) = users.takeLast(2)
            users.take(20).forEachIndexed { index, user ->
                user.supervisor = if (index % 3 == 0) bossA else bossB
            }

            it.flush()

            val expectedSubordinatesA = User.query(User::supervisor eq bossA)
            val expectedSubordinatesB = User.query(User::supervisor eq bossB)
            val expectedSubordinates = expectedSubordinatesA union expectedSubordinatesB

            val actualSubordinatesA = User.query(User::supervisor inEntities users.take(5) + bossA)
            val actualSubordinatesB = User.query(User::supervisor inEntities users.take(5) + bossB)
            val actualSubordinates = User.query(User::supervisor inEntities users.take(5) + bossA + bossB)

            assertThat(actualSubordinatesA.toList(), containsInAnyOrder(*expectedSubordinatesA.toList().toTypedArray()))
            assertThat(actualSubordinatesB.toList(), containsInAnyOrder(*expectedSubordinatesB.toList().toTypedArray()))
            assertThat(actualSubordinates.toList(), containsInAnyOrder(*expectedSubordinates.toList().toTypedArray()))

            assertTrue(User.query(User::supervisor inEntities users.take(3)).isEmpty)
        }
    }

    private fun <T : XdEntity> measure(
            randomCharms: List<String>,
            iterationCount: Int,
            expectedCount: Int,
            action: (List<String>) -> XdQuery<T>
    ) : Long {
        val random = Random()

        return store.transactional {
            val start = System.currentTimeMillis()
            (1..iterationCount).forEach {
                val charms = randomCharms.randomSubset(expectedCount, random)
                val xdQuery = action(charms)
                assertThat(xdQuery.toList().size, equalTo(expectedCount))
            }
            System.currentTimeMillis() - start
        }
    }

    private fun List<String>.randomSubset(subsetLength: Int, random: Random = Random()): List<String> {
        return when {
            subsetLength >= this.size -> this
            else -> (2..subsetLength).fold(listOf(random.nextInt(this.size))) { indexes, _ -> indexes + ((indexes.last() + 1) % this.size) }.map {
                this[it]
            }
        }
    }
}