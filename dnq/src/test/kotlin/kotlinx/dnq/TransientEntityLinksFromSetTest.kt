/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.iterate.EntityIteratorWithPropId
import org.junit.Test

class TransientEntityLinksFromSetTest : DBTest() {

    class Issue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Issue>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Issue)
    }

    @Test
    fun testAll() {
        val (i1, i2, i3, i4) = transactional { txn ->
            (1..4).map { txn.newEntity("Issue") }
        }

        val names = setOf("dup", "hup")

        transactional { txn ->
            DirectedAssociationSemantics.createToMany(i2, "dup", i3)
            DirectedAssociationSemantics.createToMany(i1, "hup", i4)
            DirectedAssociationSemantics.createToMany(i1, "hup", i3)
            DirectedAssociationSemantics.createToMany(i1, "dup", i2)

            assertThat(i1.getAddedLinks(names).toNamesAndEntities())
                    .containsExactly("dup" to i2, "hup" to i3, "hup" to i4, "hup" to null)
            assertThat(i2.getAddedLinks(names).toNamesAndEntities())
                    .containsExactly("dup" to i3, "dup" to null)

            assertThat(i1.readonlyCopy(txn).getAddedLinks(names).toNamesAndEntities())
                    .containsExactly("dup" to i2, "hup" to i3, "hup" to i4, "hup" to null)
            assertThat(i2.readonlyCopy(txn).getAddedLinks(names).toNamesAndEntities())
                    .containsExactly("dup" to i3, "dup" to null)

            assertThat(AssociationSemantics.getRemovedLinks(i1, names)).isEmpty()
            assertThat(AssociationSemantics.getRemovedLinks(i2, names)).isEmpty()
        }

        transactional {
            assertThat(AssociationSemantics.getToMany(i1, names).toNamesAndEntities())
                    .containsExactly("dup" to i2, "hup" to i3, "hup" to i4, "hup" to null)
            assertThat(AssociationSemantics.getToMany(i2, names).toNamesAndEntities())
                    .containsExactly("dup" to i3, "dup" to null)
        }

        transactional { txn ->
            DirectedAssociationSemantics.removeToMany(i1, "dup", i2)
            DirectedAssociationSemantics.removeToMany(i1, "hup", i3)
            DirectedAssociationSemantics.removeToMany(i1, "hup", i4)
            DirectedAssociationSemantics.removeToMany(i2, "dup", i3)

            assertThat(AssociationSemantics.getRemovedLinks(i1, names).toNamesAndEntities())
                    .containsExactly("dup" to i2, "hup" to i3, "hup" to i4, "hup" to null)
            assertThat(AssociationSemantics.getRemovedLinks(i2, names).toNamesAndEntities())
                    .containsExactly("dup" to i3, "dup" to null)

            assertThat(AssociationSemantics.getRemovedLinks(i1.readonlyCopy(txn), names).toNamesAndEntities())
                    .containsExactly("dup" to i2, "hup" to i3, "hup" to i4, "hup" to null)
            assertThat(AssociationSemantics.getRemovedLinks(i2.readonlyCopy(txn), names).toNamesAndEntities())
                    .containsExactly("dup" to i3, "dup" to null)

            assertThat(i1.getAddedLinks(names)).isEmpty()
            assertThat(i2.getAddedLinks(names)).isEmpty()
        }
    }

    @Test
    fun testWD_2060() {
        transactional { txn ->
            val i = txn.newEntity("Issue").readonlyCopy(txn)
            assertThat(AssociationSemantics.getToManyList(i, "nonExistentLinkName")).isEmpty()
        }
    }

    private fun TransientEntity.readonlyCopy(txn: TransientStoreSession) =
            txn.transientChangesTracker.getSnapshotEntity(this)

    private fun Iterable<Entity>.toNamesAndEntities(): List<Pair<String, Entity?>> {
        return sequence {
            val iterator = iterator() as EntityIteratorWithPropId
            yieldAll(iterator.asSequence().map { iterator.currentLinkName() to it })
            yield(iterator.currentLinkName() to null)
        }.toList()
    }
}
