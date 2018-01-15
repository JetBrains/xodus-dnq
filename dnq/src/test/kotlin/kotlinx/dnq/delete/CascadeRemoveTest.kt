package kotlinx.dnq.delete


import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.toList
import kotlinx.dnq.transactional
import org.junit.Test

class CascadeRemoveTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(RIssue, RIssuePart, RIssueSubpart, RUser, RRole)
    }

    @Test
    fun removeParentWithSingleChildWithCascadeDelete() {
        val issue = store.transactional {
            val part = RIssuePart.new()
            RIssue.new { singleCascadePart = part }
        }
        store.transactional {
            issue.delete()
        }
        store.transactional {
            assertThat(RIssue.all().toList()).isEmpty()
            assertThat(RIssuePart.all().toList()).isEmpty()
        }
    }

    @Test
    fun removeParentWithMultipleChildWithCascadeDelete() {
        val issue = store.transactional {
            val issue = RIssue.new()
            val part1 = RIssuePart.new()
            val part2 = RIssuePart.new()
            issue.multipleCascadePart.add(part1)
            part2.multipleCascadeParent = issue
            issue
        }
        store.transactional {
            issue.delete()
        }
        store.transactional {
            assertThat(RIssue.all().toList()).isEmpty()
            assertThat(RIssuePart.all().toList()).isEmpty()
        }
    }

    @Test
    fun removeCascadeByCascadeDelete() {
        val issue = store.transactional {
            val issue = RIssue.new()
            val part1 = RIssuePart.new {
                subparts.add(RIssueSubpart.new())
            }
            val part2 = RIssuePart.new {
                subparts.add(RIssueSubpart.new())
            }
            issue.multipleCascadePart.add(part1)
            part2.multipleCascadeParent = issue

            issue
        }
        store.transactional {
            issue.delete()
        }
        store.transactional {
            assertThat(RIssue.all().toList()).isEmpty()
            assertThat(RIssuePart.all().toList()).isEmpty()
            assertThat(RIssueSubpart.all().toList()).isEmpty()
        }
    }
}
