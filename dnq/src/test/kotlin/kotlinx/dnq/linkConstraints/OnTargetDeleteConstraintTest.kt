/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
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
package kotlinx.dnq.linkConstraints


import com.google.common.truth.Truth.assertThat
import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.metadata.AssociationEndCardinality
import jetbrains.exodus.query.metadata.AssociationType
import jetbrains.exodus.query.metadata.ModelMetaDataImpl
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.toList
import kotlinx.dnq.util.addLink
import org.junit.Test
import kotlin.test.assertFailsWith


class OnTargetDeleteConstraintTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(
                A, BaseDynamicEntity, SubDynamicEntity,
                BaseStaticEntity, SubStaticEntity,
                B0, B1, B2,
                C0, C1,
                E0, E1, E2
        )
    }


    class A(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<A>()
    }

    class C0(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<C0>()

        val toC1 by xdLink0_N(C1::toC0)
    }

    class C1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<C1>()

        var toC0: C0? by xdLink0_1(C0::toC1, onTargetDelete = OnDeletePolicy.CASCADE)
    }

    @Test
    fun ManyTargetToOneSourceOnTargetDeleteCascade() {
        // association end type      : Undirected
        // to target end constraint  : OnTargetDelete(cascade)
        // to target end cardinality : [..n]
        // to source end constraint  : ---
        // to source end cardinality : [..1]
        // result                    : incoming links error
        val c1 = store.transactional {
            val c1 = C1.new()
            val c0 = C0.new()
            c0.toC1.add(C1.new())
            c0.toC1.add(c1)
            c1
        }
        assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                c1.delete()
            }
        }
    }

    class B0(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<B0>()

        val toB1 by xdLink0_N(B1::toB0)
        val toB2 by xdLink0_N(B2::toB0)
    }

    class B1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<B1>()

        var toB0: B0? by xdLink0_1(B0::toB1, onTargetDelete = OnDeletePolicy.CASCADE)
    }

    class B2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<B2>()

        var toB0: B0? by xdLink0_1(B0::toB2, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    @Test
    fun OneTargetToManySourceOnTargetDeleteCascade() {
        // association end type      : Undirected
        // to target end constraint  : OnTargetDelete(cascade)
        // to target end cardinality : [..1]
        // to source end constraint  : ---
        // to source end cardinality : [..n]
        // result                    : target will initiate all sources deletion

        val b0 = store.transactional {
            // target
            val b0 = B0.new()
            val b1 = B1.new()
            b0.toB1.add(b1)
            b0.toB1.add(B1.new())
            assertThat(b1.toB0).isNotNull()
            assertThat(b0.toB1.toList()).hasSize(2)
            b0
        }
        store.transactional {
            b0.delete()
        }
        store.transactional {
            assertThat(B0.all().toList()).isEmpty()
            assertThat(B1.all().toList()).isEmpty()
        }
    }

    @Test
    fun OneTargetToManySourceOnTargetDeleteClear() {
        // association end type      : Undirected
        // to target end constraint  : OnTargetDelete(clear)
        // to target end cardinality : [..1]
        // to source end constraint  : ---
        // to source end cardinality : [..n]
        // result                    : associations with b0 will be removed everywhere

        val b0 = store.transactional {
            val b0 = B0.new()
            b0.toB2.add(B2.new())
            b0.toB2.add(B2.new())
            b0.toB2.add(B2.new())
            b0.toB2.add(B2.new())
            b0
        }
        store.transactional {
            b0.delete()
        }
        store.transactional {
            assertThat(B0.all().toList()).hasSize(0)
            assertThat(B2.all().toList()).hasSize(4)
        }
    }

    class E0(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<E0>()

        val toE1 by xdLink0_N(E1::toE0, onTargetDelete = OnDeletePolicy.CLEAR)
        val toE2 by xdLink0_N(E2::toE0, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    class E1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<E1>()

        var toE0: E0? by xdLink0_1(E0::toE1)
    }

    class E2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<E2>()

        val toE0: XdMutableQuery<E0> by xdLink0_N(E0::toE2)
    }

    @Test
    fun UndirectedOneSourceToManyTargetsOnTargetDeleteClear() {
        // association end type      : Undirected
        // to target end constraint  : OnTargetDelete(clear)
        // to target end cardinality : [..n]
        // to source end constraint  : ---
        // to source end cardinality : [..1]
        // result                    : one association (e0 with e1) will be removed
        val (e0, e1) = store.transactional {
            val e0 = E0.new()
            val e1 = E1.new()

            e0.toE1.add(e1)
            e0.toE1.add(E1.new())
            e0.toE1.add(E1.new())

            Pair(e0, e1)
        }
        store.transactional {
            e1.delete()
        }
        store.transactional {
            assertThat(e0.toE1.toList()).hasSize(2)
        }
    }

    @Test
    fun UndirectedManySourcesToManyTargetsOnTargetDeleteClear() {
        // association end type      : Undirected
        // to target end constraint  : OnTargetDelete(clear)
        // to target end cardinality : [..n]
        // to source end constraint  : ---
        // to source end cardinality : [..n]
        // result                    : all associations with e2[0] will be removed
        val (e0List, e2List) = store.transactional {
            val e0List = (0..2).map { E0.new() }
            val e2List = (0..2).map { E2.new() }

            e0List.forEach { e0 ->
                e2List.forEach { e2 -> e0.toE2.add(e2) }
            }
            Pair(e0List, e2List)
        }
        store.transactional {
            for (e0 in e0List) {
                assertThat(e0.toE2.toList()).hasSize(3)
            }
        }
        store.transactional {
            e2List.first().delete()
        }
        store.transactional {
            for (e0 in e0List) {
                assertThat(e0.toE2.toList()).hasSize(2)
            }
        }
    }

    abstract class BaseDynamicEntity(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<BaseDynamicEntity>() {

            fun registerAssociations(modelMetaDataImpl: ModelMetaDataImpl) {
                registerDirectedAssociationWithTargetDeleteConstraint(
                        modelMetaDataImpl,
                        BaseDynamicEntity::clearable.name,
                        OnDeletePolicy.CLEAR
                )
                registerDirectedAssociationWithTargetDeleteConstraint(
                        modelMetaDataImpl,
                        BaseDynamicEntity::deletable.name,
                        OnDeletePolicy.CASCADE
                )
            }

            private fun registerDirectedAssociationWithTargetDeleteConstraint(
                    modelMetaDataImpl: ModelMetaDataImpl,
                    associationName: String,
                    onTargetDelete: OnDeletePolicy) {

                if (!modelMetaDataImpl.hasAssociation(entityType, A.entityType, associationName)) {
                    val onTargetDeleteCascade = onTargetDelete == OnDeletePolicy.CASCADE
                    val onTargetDeleteClear = onTargetDelete == OnDeletePolicy.CLEAR

                    modelMetaDataImpl.addLink(
                            sourceEntityName = entityType, targetEntityName = A.entityType,
                            type = AssociationType.Directed, sourceName = associationName, sourceCardinality = AssociationEndCardinality._0_1,
                            sourceCascadeDelete = false, sourceClearOnDelete = false,
                            sourceTargetCascadeDelete = onTargetDeleteCascade, sourceTargetClearOnDelete = onTargetDeleteClear,
                            targetName = null,
                            targetCardinality = null,
                            targetCascadeDelete = false,
                            targetClearOnDelete = false,
                            targetTargetCascadeDelete = false,
                            targetTargetClearOnDelete = false
                    )
                }
            }
        }

        var clearable: A?
            get() = AssociationSemantics.getToOne(entity, BaseDynamicEntity::clearable.name)?.toXd()
            set(value) = DirectedAssociationSemantics.setToOne(entity, BaseDynamicEntity::clearable.name, value?.entity)

        var deletable: A?
            get() = AssociationSemantics.getToOne(entity, BaseDynamicEntity::deletable.name)?.toXd()
            set(value) = DirectedAssociationSemantics.setToOne(entity, BaseDynamicEntity::deletable.name, value?.entity)
    }

    class SubDynamicEntity(entity: Entity) : BaseDynamicEntity(entity) {
        companion object : XdNaturalEntityType<SubDynamicEntity>() {
            fun new(weakTarget: A?, strongTarget: A) = new {
                clearable = weakTarget
                deletable = strongTarget
            }
        }
    }

    abstract class BaseStaticEntity(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<BaseStaticEntity>()

        var clearable by xdLink0_1(A, onTargetDelete = OnDeletePolicy.CLEAR)
        var deletable by xdLink1(A, onTargetDelete = OnDeletePolicy.CASCADE)
    }

    class SubStaticEntity(entity: Entity) : BaseStaticEntity(entity) {
        companion object : XdNaturalEntityType<SubStaticEntity>() {
            fun new(weakTarget: A?, strongTarget: A) = new {
                clearable = weakTarget
                deletable = strongTarget
            }
        }
    }

    @Test
    fun OnTargetDeleteAbstractBaseClass_StaticConstraintDeclaration() {
        val (weakTarget, strongTarget) = store.transactional {
            Pair(A.new(), A.new())
        }
        val source = store.transactional {
            SubStaticEntity.new(weakTarget, strongTarget)
        }
        store.transactional {
            assertThat(source.clearable).isEqualTo(weakTarget)
        }
        // clear association on target delete
        store.transactional {
            weakTarget.delete()
            assertThat(source.clearable).isNull()
        }
        store.transactional {
            assertThat(source.clearable).isNull()
        }
        // delete source on target delete
        store.transactional {
            strongTarget.delete()
        }
        store.transactional {
            assertThat(BaseStaticEntity.all().toList()).isEmpty()
            assertThat(SubStaticEntity.all().toList()).isEmpty()
        }
    }

    @Test
    fun OnTargetDeleteAbstractBaseClass_DynamicConstraintDeclaration() {
        val (weakTarget, strongTarget) = store.transactional {
            Pair(A.new(), A.new())
        }
        store.transactional {
            BaseDynamicEntity.registerAssociations(store.modelMetaData as ModelMetaDataImpl)
        }

        val source = store.transactional {
            SubDynamicEntity.new(weakTarget, strongTarget)
        }
        store.transactional {
            assertThat(source.clearable).isEqualTo(weakTarget)
        }
        // clear association on target delete
        store.transactional {
            weakTarget.delete()
            assertThat(source.clearable).isNull()
        }
        store.transactional {
            assertThat(source.clearable).isNull()
        }
        // delete source on target delete
        store.transactional {
            strongTarget.delete()
        }
        store.transactional {
            assertThat(BaseDynamicEntity.all().toList()).isEmpty()
            assertThat(SubDynamicEntity.all().toList()).isEmpty()
        }
    }
}
