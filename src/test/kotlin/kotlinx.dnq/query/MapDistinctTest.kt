package kotlinx.dnq.query

import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test


class MapDistinctTest : DBTest() {

    @Test
    fun `mapDistinct should work with xdLink`() {
        store.transactional {
            User.all().mapDistinct(User::supervisor).let {
                assertEquals(1, it.size())
                assertEquals(1, it.asSequence().count())
            }
            User.all().mapDistinct { it.supervisor }.let {
                assertEquals(1, it.size())
                assertEquals(1, it.asSequence().count())
            }
        }
    }

    @Test
    fun `flatMapDistinct should work with xdLink`() {
        store.transactional {
            User.all().flatMapDistinct(User::contacts).let {
                assertEquals(2, it.size())
                assertEquals(2, it.asSequence().count())
            }
            User.all().flatMapDistinct { it.contacts }.let {
                assertEquals(2, it.size())
                assertEquals(2, it.asSequence().count())
            }
        }
    }

    @Test
    fun `mapDistinct should work with xdParent`() {
        store.transactional {
            Fellow.all().mapDistinct(Fellow::team).let {
                assertEquals(1, it.size())
                assertEquals(1, it.asSequence().count())
            }
            Fellow.all().mapDistinct { it.team }.let {
                assertEquals(1, it.size())
                assertEquals(1, it.asSequence().count())
            }
        }
    }

    @Test
    fun `flatMapDistinct should work with xdChildren`() {
        store.transactional {
            Team.all().flatMapDistinct(Team::fellows).let {
                assertEquals(2, it.size())
                assertEquals(2, it.asSequence().count())
            }
            Team.all().flatMapDistinct { it.fellows }.let {
                assertEquals(2, it.size())
                assertEquals(2, it.asSequence().count())
            }
        }
    }


    @Before
    fun initStructure() {
        val boss = store.transactional {
            User.new {
                login = "boss"
                skill = 1
            }
        }
        store.transactional {
            User.new {
                login = "fellow"
                supervisor = boss
                skill = 1
            }
        }

        val anotherGuy = store.transactional {
            User.new {
                login = "anotherFellow"
                skill = 1
            }
        }

        store.transactional {
            Contact.new {
                email = "boss@123.org"
                user = boss
            }
            Contact.new {
                email = "anotherFellow@123.org"
                user = anotherGuy
            }
        }
        store.transactional {

            val jb = Team.new {
                name = "jb"
            }
            Team.new {
                name = "epam"
            }

            Fellow.new {
                name = "fellow1"
                team = jb
            }

            Fellow.new {
                name = "fellow2"
                team = jb
            }
        }
    }


}
