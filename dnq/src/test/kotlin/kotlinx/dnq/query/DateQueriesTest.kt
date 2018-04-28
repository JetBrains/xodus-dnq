package kotlinx.dnq.query

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.joda.time.DateTime
import org.junit.Before
import org.junit.Test

class DateQueriesTest : DBTest() {
    class Issue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Issue>() {
            fun new(name: String) = new {
                this.name = name
                this.created = DateTime.now()
            }
        }

        var name by xdStringProp()
        var created by xdRequiredDateTimeProp()
    }

    lateinit var i1: Issue
    lateinit var momentBetween: DateTime
    lateinit var i2: Issue

    override fun registerEntityTypes() {
        XdModel.registerNodes(Issue)
    }

    @Before
    fun test1() {
        i1 = transactional { Issue.new("issue1") }
        Thread.sleep(20)

        momentBetween = DateTime.now()
        Thread.sleep(20)

        i2 = transactional { Issue.new("issue2") }
        Thread.sleep(20)
    }

    @Test
    fun equals() {
        transactional {
            assertThat(Issue.query(Issue::created eq DateTime.now())).hasSize(0)
        }
    }

    @Test
    fun range() {
        transactional {
            assertThat(Issue.query((i1.created.hourOfDay().roundFloorCopy()..i2.created.hourOfDay().roundCeilingCopy()) contains Issue::created)).hasSize(2)
        }
    }

    @Test
    fun `less or equal`() {
        transactional {
            assertThat(Issue.query(Issue::created le momentBetween)).hasSize(1)
        }
    }

    @Test
    fun `greater or equal`() {
        transactional {
            assertThat(Issue.query(Issue::created ge momentBetween)).hasSize(1)
        }
    }

    @Test
    fun `greater than now`() {
        transactional {
            assertThat(Issue.query(Issue::created gt DateTime.now())).hasSize(0)
        }
    }

    @Test
    fun `less than now`() {
        transactional {
            assertThat(Issue.query(Issue::created lt DateTime.now())).hasSize(2)
        }
    }

    @Test
    fun `less than moment between`() {
        transactional {
            assertThat(Issue.query(Issue::created lt momentBetween)).containsExactly(i1)
        }
    }

    @Test
    fun `greater than moment between`() {
        transactional {
            assertThat(Issue.query(Issue::created gt momentBetween)).containsExactly(i2)
        }
    }
}
