package kotlinx.dnq

import kotlinx.dnq.query.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class LinksTest : DBTest() {

    @Test
    fun bidirectional() {
        store.transactional {
            RootGroup.new {
                name = "Root"
                nestedGroups.add(NestedGroup.new { name = "A"})
                nestedGroups.add(NestedGroup.new { name = "B"})
                nestedGroups.add(NestedGroup.new { name = "C"})
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
}