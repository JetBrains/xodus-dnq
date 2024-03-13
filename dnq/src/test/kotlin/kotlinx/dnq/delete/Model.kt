/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
package kotlinx.dnq.delete

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.link.OnDeletePolicy.CLEAR


class Foo(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Foo>()

    var intField by xdIntProp()
}

class RIssue(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<RIssue>()

    var reporter by xdLink0_1(RUser)
    var singleCascadePart by xdChild0_1(RIssuePart::singleCascadeParent)
    val multipleCascadePart by xdChildren0_N(RIssuePart::multipleCascadeParent)

    override fun destructor() {
        println("destructor called")
    }
}

class RIssuePart(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<RIssuePart>()

    var singleCascadeParent: RIssue? by xdMultiParent(RIssue::singleCascadePart)
    var multipleCascadeParent: RIssue? by xdMultiParent(RIssue::multipleCascadePart)

    val subparts by xdChildren0_N(RIssueSubpart::issuePart)
}

class RIssueSubpart(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<RIssueSubpart>()

    var issuePart: RIssuePart by xdParent(RIssuePart::subparts)
}

class RUser(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<RUser>() {
        fun new(name: String) = new {
            this.name = name
        }
    }

    var name by xdStringProp()
    var role by xdLink0_1(RRole)
}

class RRole(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<RRole>()
}

class RMockProject(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<RMockProject>()

    val fields by xdChildren0_N(RMockProjectField::project)
}

class RMockProjectField(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<RMockProjectField>() {
        fun new(prototype: RMockPrototype, project: RMockProject) = new {
            this.prototype = prototype
            this.project = project
        }
    }

    var prototype: RMockPrototype by xdLink1(RMockPrototype::instances)
    var project: RMockProject by xdParent(RMockProject::fields)
}

class RMockPrototype(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<RMockPrototype>()

    val instances by xdLink0_N(RMockProjectField::prototype, onDelete = CASCADE, onTargetDelete = CLEAR)
}
