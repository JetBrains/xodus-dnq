/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.query

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.xdChildren0_N
import kotlinx.dnq.xdIntProp
import kotlinx.dnq.xdLink0_N
import kotlinx.dnq.xdParent
import kotlinx.dnq.xdStringProp
import org.junit.Test

class SprintSearchSortRegressionTest : DBTest() {
    class Board(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Board>()

        val projects: XdMutableQuery<Project> by xdChildren0_N(Project::board)
        val sprints: XdMutableQuery<Sprint> by xdChildren0_N(Sprint::board)
    }

    class Project(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Project>()

        var shortName by xdStringProp()
        val issues: XdMutableQuery<Issue> by xdChildren0_N(Issue::project)
        var board: Board by xdParent(Board::projects)
    }

    class Issue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Issue>()

        var numberInProject by xdIntProp()
        var project: Project by xdParent(Project::issues)
        val sprints: XdMutableQuery<Sprint> by xdLink0_N(
            Sprint::issues,
            dbPropertyName = "_sprints",
            dbOppositePropertyName = "_issues"
        )
    }

    class Sprint(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Sprint>()

        var ordinal by xdIntProp()
        var board: Board by xdParent(Board::sprints)
        val issues: XdMutableQuery<Issue> by xdLink0_N(
            Issue::sprints,
            dbPropertyName = "_issues",
            dbOppositePropertyName = "_sprints"
        )
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Board, Project, Issue, Sprint)
    }

    @Test
    fun `wildcard sprint range preserves issue id order after project intersection`() {
        val data = transactional { txn ->
            val board = Board.new {}
            val project = Project.new {
                this.board = board
                shortName = "agile"
            }
            val s1 = Sprint.new { this.board = board; ordinal = 1 }
            val s2 = Sprint.new { this.board = board; ordinal = 2 }
            val s3 = Sprint.new { this.board = board; ordinal = 3 }
            Sprint.new { this.board = board; ordinal = 4 }
            Sprint.new { this.board = board; ordinal = 5 }

            val i2 = Issue.new { numberInProject = 2; this.project = project }
            val i3 = Issue.new { numberInProject = 3; this.project = project }
            val i5 = Issue.new { numberInProject = 5; this.project = project }
            val i6 = Issue.new { numberInProject = 6; this.project = project }
            val i7 = Issue.new { numberInProject = 7; this.project = project }
            Issue.new { numberInProject = 8; this.project = project }
            Issue.new { numberInProject = 9; this.project = project }
            txn.flush()

            Triple(board, project, listOf(s1, s2, s3) to listOf(i2, i3, i5, i6, i7))
        }

        transactional { txn ->
            val (s1, s2, s3) = data.third.first
            val issues = data.third.second
            s1.issues.add(issues[0])
            s1.issues.add(issues[1])
            s1.issues.add(issues[2])
            s2.issues.add(issues[1])
            s3.issues.add(issues[3])
            s3.issues.add(issues[4])
            txn.flush()
        }

        transactional {
            val (board, project) = data
            val selectedSprints = board.sprints
                .query((Sprint::ordinal le 3) and (Sprint::ordinal ge 0))
                .toSet()
            val sprintIssues = selectedSprints.asIterable().fold(Issue.emptyQuery()) { query, sprint ->
                query union sprint.issues
            }
            val result = (project.issues intersect sprintIssues)
                .sortedBy(Issue::numberInProject, asc = false)
                .sortedBy(Issue::project, Project::shortName, asc = false)
                .toList()

            assertThat(result.map { it.numberInProject })
                .containsExactly(7, 6, 5, 3, 2)
                .inOrder()
        }
    }
}
