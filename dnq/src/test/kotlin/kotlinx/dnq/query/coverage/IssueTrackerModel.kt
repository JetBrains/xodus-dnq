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
package kotlinx.dnq.query.coverage

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
import kotlinx.dnq.xdBooleanProp
import kotlinx.dnq.xdIntProp
import kotlinx.dnq.xdLink0_1
import kotlinx.dnq.xdLink0_N
import kotlinx.dnq.xdLongProp
import kotlinx.dnq.xdRequiredStringProp

/**
 * DNQ entity model for query coverage tests.
 *
 * Mirrors the domain used by the low-level GremlinQueryCoverageTest so that a single
 * dataset can serve both DSL-level and GremlinQuery-level test assertions.
 *
 *   XdUser        (name, email, active)   [entity type: "User"]
 *   └── Employee  (+ department, salary)
 *       └── Manager (+ reportsCount)
 *
 *   Project  (key, name, isArchived)
 *   Issue    (key, summary, priority, status, estimate)
 *   Sprint   (key, name, state, velocity)
 *   Tag      (name, color)
 *
 * Bidirectional links (reverse traversal needed for FollowLink queries):
 *   Issue   <--project-->        Project
 *   Issue   <--assignee-->       Employee
 *   Issue   <--sprint-->         Sprint
 *
 * Unidirectional links:
 *   Issue   --tags-->     Tag
 *   Issue   --parent-->   Issue    (self-referential; subtasks)
 *   Project --lead-->     Employee
 *   Sprint  --project-->  Project  (separate from Issue--project link)
 */

open class XdUser(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdUser>("User")

    var name       by xdRequiredStringProp()
    var email      by xdRequiredStringProp()
    var active     by xdBooleanProp()
}

open class Employee(entity: Entity) : XdUser(entity) {
    companion object : XdNaturalEntityType<Employee>()

    var department by xdRequiredStringProp()
    var salary     by xdLongProp()

    val assignedIssues: XdMutableQuery<Issue> by xdLink0_N(Issue::assignee)
}

class Manager(entity: Entity) : Employee(entity) {
    companion object : XdNaturalEntityType<Manager>()

    var reportsCount by xdIntProp()
}

class Project(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Project>()

    var key        by xdRequiredStringProp()
    var name       by xdRequiredStringProp()
    var isArchived by xdBooleanProp()
    var lead: Employee? by xdLink0_1(Employee)

    val issues:  XdMutableQuery<Issue>  by xdLink0_N(Issue::project,  onDelete = CLEAR, onTargetDelete = CLEAR)
}

class Issue(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Issue>()

    var key      by xdRequiredStringProp()
    var summary  by xdRequiredStringProp()
    var priority by xdRequiredStringProp()
    var status   by xdRequiredStringProp()
    var estimate by xdIntProp()

    var project:  Project?  by xdLink0_1(Project::issues,           onDelete = CLEAR, onTargetDelete = CLEAR)
    var assignee: Employee? by xdLink0_1(Employee::assignedIssues,  onDelete = CLEAR, onTargetDelete = CLEAR)
    var sprint:   Sprint?   by xdLink0_1(Sprint::issues,            onDelete = CLEAR, onTargetDelete = CLEAR)
    val tags:     XdMutableQuery<Tag> by xdLink0_N(Tag,             onDelete = CLEAR, onTargetDelete = CLEAR)
    var parent:   Issue?    by xdLink0_1(Issue)
}

class Sprint(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Sprint>()

    var key      by xdRequiredStringProp()
    var name     by xdRequiredStringProp()
    var state    by xdRequiredStringProp()
    var velocity by xdIntProp()

    val issues:  XdMutableQuery<Issue> by xdLink0_N(Issue::sprint, onDelete = CLEAR, onTargetDelete = CLEAR)
    var project: Project? by xdLink0_1(Project)
}

class Tag(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Tag>()

    var name  by xdRequiredStringProp()
    var color by xdRequiredStringProp()
}
