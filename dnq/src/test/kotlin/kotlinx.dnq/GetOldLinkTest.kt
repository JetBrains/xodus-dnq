package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.util.getOldValue
import org.junit.Test

class GetOldLinkTest : DBTest() {

    @Test
    fun `if link is changed getOldValue should return old value`() {
        val root1 = store.transactional {
            RootGroup.new { name = "root group 1" }
        }

        val nested = store.transactional {
            NestedGroup.new {
                name = "nested"
                owner = User.new { login = "owner"; skill = 1 }
                parentGroup = root1
            }
        }

        val root2 = store.transactional {
            RootGroup.new { name = "root group 2" }
        }

        store.transactional {
            nested.parentGroup = root2
            assertThat(nested.getOldValue(NestedGroup::parentGroup)).isEqualTo(root1)
        }
    }


    @Test
    fun `getOldValue for new entity should return null`() {
        store.transactional {
            val nested = NestedGroup.new {
                name = "nested"
                owner = User.new { login = "owner"; skill = 1 }
                parentGroup = RootGroup.new { name = "root group 1" }
            }
            assertThat(nested.getOldValue(NestedGroup::parentGroup)).isNull()
        }
    }

    @Test
    fun `if value did not change getOldValue should return the current value`() {
        val root = store.transactional {
            RootGroup.new { name = "root group 1" }
        }

        val nested = store.transactional {
            NestedGroup.new {
                name = "nested"
                owner = User.new { login = "owner"; skill = 1 }
                parentGroup = root
            }
        }

        store.transactional {
            assertThat(nested.getOldValue(NestedGroup::parentGroup)).isEqualTo(root)
        }
    }
}