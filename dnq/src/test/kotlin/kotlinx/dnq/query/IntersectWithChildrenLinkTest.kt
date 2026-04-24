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

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.junit.Test

/**
 * Reproducer for a bug in O7 optimizer when `Labeled(Where(All), T)` is intersected with
 * a `FollowLink` query (e.g., from a `xdChildren0_N` link traversal).
 *
 * The O7 symmetric path in `combineEfficient` extracted the condition from the left operand
 * via `extractCondition(Labeled(Where(All), "UserField"))` which returned `All` — a no-op
 * block. O7 then appended `All` to the FollowLink (doing nothing) and returned the result,
 * silently dropping the `hasLabel("UserField")` filter from the `Labeled` wrapper. The
 * intersection returned all linked entities regardless of type.
 *
 * Fixed by adding an `All` guard to O7: when `extractCondition` returns `All`, the
 * optimization is skipped and the query falls through to `Aggregate`, which correctly
 * applies both the type filter and the link traversal.
 */
class IntersectWithChildrenLinkTest : DBTest() {

    class Project(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Project>()

        val fields by xdChildren0_N(Field::project)
    }

    abstract class Field(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Field>()

        var project: Project by xdParent(Project::fields)
    }

    class UserField(entity: Entity) : Field(entity) {
        companion object : XdNaturalEntityType<UserField>()
    }

    class EnumField(entity: Entity) : Field(entity) {
        companion object : XdNaturalEntityType<EnumField>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Project, UserField, EnumField)
    }

    /**
     * `UserField.all()` returns 1 entity.
     * `project.fields` returns 3 entities (1 UserField + 2 EnumFields).
     * Their intersection must return exactly the 1 UserField — not all 3 fields.
     */
    @Test
    fun `intersect of specific subtype with parent children link returns only that subtype`() {
        val project = transactional {
            Project.new().also { p ->
                p.fields.add(UserField.new())
                p.fields.add(EnumField.new())
                p.fields.add(EnumField.new())
            }
        }

        transactional {
            val userFields = UserField.all().intersect(project.fields)
            assertQuery(userFields).hasSize(1)
        }
    }

    /**
     * Same setup with two projects to ensure the intersect doesn't bleed across projects.
     * `UserField.all()` returns 2 entities (one per project).
     * `project1.fields` returns 3 entities (1 UserField + 2 EnumFields).
     * Their intersection must return exactly the 1 UserField from project1.
     */
    @Test
    fun `intersect with parent children link does not include entities from other projects`() {
        val (project1, _) = transactional {
            val p1 = Project.new().also { p ->
                p.fields.add(UserField.new())
                p.fields.add(EnumField.new())
                p.fields.add(EnumField.new())
            }
            val p2 = Project.new().also { p ->
                p.fields.add(UserField.new())
                p.fields.add(EnumField.new())
            }
            Pair(p1, p2)
        }

        transactional {
            val userFields = UserField.all().intersect(project1.fields)
            assertQuery(userFields).hasSize(1)
        }
    }
}
