package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.creator.findOrNew
import kotlinx.dnq.query.addAll
import kotlinx.dnq.query.and
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.query
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FindOrCreateTest : DBTest() {

    class ApprovedScope(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<ApprovedScope>() {
            override val compositeIndices = listOf(
                    listOf(ApprovedScope::user, ApprovedScope::groupsConvolution)
            )

            fun findOrNew(user: User, groups: Sequence<Group>): ApprovedScope {
                val groupsConvolution = groups.map { it.entityId }.sorted().joinToString(":")

                return findOrNew(query((ApprovedScope::user eq user) and (ApprovedScope::groupsConvolution eq groupsConvolution))) {
                    this.user = user
                    this.groups.addAll(groups)
                    this.groupsConvolution = groupsConvolution
                }
            }
        }

        var user by xdLink1(User)
        val groups by xdLink0_N(Group)
        var groupsConvolution by xdRequiredStringProp()
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(ApprovedScope)
    }

    @Test
    fun `sequential creation should return the same entity`() {
        val user = store.transactional {
            User.new { login = "zeckson"; skill = 1 }
        }
        val groups = store.transactional {
            sequenceOf(RootGroup.new { name = "A" }, RootGroup.new { name = "B" })
        }
        val approvedScope1 = store.transactional {
            ApprovedScope.findOrNew(user, groups)
        }
        val approvedScope2 = store.transactional {
            ApprovedScope.findOrNew(user, groups)
        }
        store.transactional {
            assertEquals(approvedScope1, approvedScope2)
        }
    }

    @Test
    fun `parallel creation should return the same entity`() {
        val user = store.transactional {
            User.new { login = "zeckson"; skill = 1 }
        }
        val groups = store.transactional {
            sequenceOf(RootGroup.new { name = "A" }, RootGroup.new { name = "B" })
        }
        val (approvedScope1, approvedScope2) = store.transactional {
            val approvedScope2 = store.transactional(isNew = true) {
                ApprovedScope.findOrNew(user, groups)
            }
            Pair(ApprovedScope.findOrNew(user, groups), approvedScope2)
        }
        store.transactional {
            assertEquals(approvedScope1, approvedScope2)
        }
    }

    @Test
    fun `different parameters should result into different entities`() {
        val user = store.transactional {
            User.new { login = "zeckson"; skill = 1 }
        }
        val groups = store.transactional {
            sequenceOf(RootGroup.new { name = "A" }, RootGroup.new { name = "B" })
        }
        val approvedScope1 = store.transactional {
            ApprovedScope.findOrNew(user, groups)
        }
        val approvedScope2 = store.transactional {
            ApprovedScope.findOrNew(user, groups.take(1))
        }
        store.transactional {
            assertNotEquals(approvedScope1, approvedScope2)
        }
    }
}