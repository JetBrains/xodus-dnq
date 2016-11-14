package kotlinx.dnq

import kotlinx.dnq.query.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class LinksTest : DBTest() {

    @Test
    fun `bidirectional many to many`() {
        store.transactional {
            RootGroup.new {
                name = "Root"
                nestedGroups.add(NestedGroup.new { name = "A" })
                nestedGroups.add(NestedGroup.new { name = "B" })
                nestedGroups.add(NestedGroup.new { name = "C" })
            }
        }

        store.transactional {
            val root = RootGroup.query(RootGroup::name eq "Root").first()
            assertEquals(3, root.nestedGroups.size())
            listOf("a", "b", "c").forEach {
                assertTrue(root.nestedGroups.any(NestedGroup::name eq it))
            }
            root.nestedGroups.asSequence().forEach {
                assertEquals(root, it.parentGroup)
            }
        }
    }

    @Test
    fun `bidirectional many to one`() {
        val contact = store.transactional {
            Contact.new {
                this.email = "1@1.com"
                this.user = User.new {
                    login = "1"
                    skill = 1
                }
            }
        }

        store.transactional {
            assertEquals("1", contact.user.login)
        }
    }

    @Test
    fun `bidirectional one to many`() {
        val user = store.transactional {
            User.new {
                login = "1"
                skill = 1
                contacts.add(Contact.new {
                    this.email = "1@1.com"
                })
            }
        }

        store.transactional {
            assertEquals("1@1.com", user.contacts.first().email)
        }
    }
}