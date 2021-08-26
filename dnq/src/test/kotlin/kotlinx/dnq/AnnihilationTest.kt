/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import com.jetbrains.teamsys.dnq.database.threadSessionOrThrow
import jetbrains.exodus.database.LinkChange
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.toList
import kotlinx.dnq.util.getDBName
import kotlinx.dnq.util.getRemovedLinks
import org.junit.Test
import kotlin.reflect.KProperty1
import kotlin.test.fail

class AnnihilationTest : DBTest() {

    class Source(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Source>()

        val b by xdLink0_N(Target, onTargetDelete = OnDeletePolicy.CLEAR)
        var c by xdLink0_1(Target)
    }

    class Target(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Target>()

        var name by xdRequiredStringProp()
        override fun toString() = name
    }


    override fun registerEntityTypes() {
        XdModel.registerNodes(Source, Target)
    }

    @Test
    fun singleLinkAnnihilation() {
        val entity = transactional { Source.new() }
        val a = transactional { Target.new { name = "a" } }
        val b = transactional { Target.new { name = "b" } }
        val d = transactional { Target.new { name = "d" } }

        transactional {
            entity.c = a
        }

        transactional {
            entity.c = b
            entity.c = a
            entity.c = d
            entity.c = b

            // removed: a
            // added  : b

            val change = getLinkChange(entity, Source::c) ?: fail()
            assertThat(change.removedEntities?.first()?.toXd<Target>()).isEqualTo(a)
            assertThat(change.addedEntities?.first()?.toXd<Target>()).isEqualTo(b)
        }
    }

    @Test
    fun singleLinkTotalAnnihilation() {
        val entity = transactional { Source.new() }
        val a = transactional { Target.new { name = "a" } }
        val b = transactional { Target.new { name = "b" } }

        transactional {
            entity.c = a
        }
        transactional {
            entity.c = b
            entity.c = a

            assertThat(getLinkChange(entity, Source::c)).isNull()
        }
    }

    @Test
    fun deleteAnnihilation() {
        val entity = transactional { Source.new() }
        val b = transactional { Target.new { name = "b" } }

        transactional {
            // entity-> a
            entity.b.add(b)
        }
        transactional {
            b.delete()
            assertThat(entity.getRemovedLinks(Source::b).toList()).containsExactly(b)
        }
    }

    @Test
    fun multipleLinkAnnihilation() {
        val entity = transactional { Source.new() }
        val a = transactional { Target.new { name = "a" } }
        val b = transactional { Target.new { name = "b" } }
        val c = transactional { Target.new { name = "c" } }
        val d = transactional { Target.new { name = "d" } }

        transactional {
            // entity -> b, c
            entity.b.add(b)
            entity.b.add(c)
        }
        transactional {
            entity.b.add(a)
            entity.b.add(d)

            entity.b.remove(a)
            entity.b.remove(b)

            entity.b.add(b)
            entity.b.add(a)

            entity.b.remove(b)
            entity.b.remove(c)

            // removed: b,c
            // added  : a,d

            val change = getLinkChange(entity, Source::b) ?: fail()
            assertThat(change.addedEntitiesSize).isEqualTo(2)
            assertThat(change.removedEntitiesSize).isEqualTo(2)

            assertThat(change.addedEntities?.map { it.toXd<Target>() })
                    .containsExactly(a, d)
            assertThat(change.removedEntities?.map { it.toXd<Target>() })
                    .containsExactly(b, c)
        }
    }

    @Test
    fun removedAnnihilation() {
        val entity = transactional { Source.new() }
        val b = transactional { Target.new { name = "b" } }
        val c = transactional { Target.new { name = "c" } }

        transactional {
            // entity -> b, c
            entity.b.add(b)
            entity.b.add(c)
        }

        transactional {
            entity.b.remove(b)
            assertThat(entity.getRemovedLinks(Source::b).toList()).containsExactly(b)

            b.delete()
            assertThat(entity.getRemovedLinks(Source::b).toList()).containsExactly(b)
        }
    }

    @Test
    fun multipleLinkTotalAnnihilation() {
        val entity = transactional { Source.new() }
        val a = transactional { Target.new { name = "a" } }
        val b = transactional { Target.new { name = "b" } }
        val c = transactional { Target.new { name = "c" } }

        transactional {
            // entity -> b, c
            entity.b.add(b)
            entity.b.add(c)
        }
        transactional {
            // + a
            entity.b.add(a)
            // + a
            entity.b.add(c)
            // no changes
            entity.b.remove(a)
            // -b
            entity.b.remove(b)
            // no changes
            entity.b.add(b)

            val change = getLinkChange(entity, Source::b)
            assertThat(change).isNull()
        }
    }

    private fun getLinkChange(source: Source, property: KProperty1<Source, *>): LinkChange? {
        val entity = source.entity as TransientEntity
        return entity
                .store.threadSessionOrThrow
                .transientChangesTracker
                .getChangedLinksDetailed(entity)
                ?.get(property.getDBName())
    }
}