package kotlinx.dnq.query

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.junit.Test

class SizeTest : DBTest() {

    class Issue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Issue>()

        val links by xdLink0_N(IssueLink)
    }

    class IssueLink(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<IssueLink>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Issue, IssueLink)
    }

    @Test
    fun testCountAfterRemoveBeforeAndAfterFlush() {
        val issue = transactional { txn ->
            val issue = Issue.new()

            issue.links.add(IssueLink.new())
            assertThat(issue.links).hasSize(1)

            txn.flush()
            assertThat(issue.links).hasSize(1)

            issue
        }
        transactional {
            assertThat(issue.links).hasSize(1)
        }
        transactional { txn ->
            issue.links.clear()
            assertThat(issue.links).isEmpty()

            txn.flush()
            assertThat(issue.links).isEmpty()
        }
        transactional {
            assertThat(issue.links).isEmpty()
        }
    }
}
