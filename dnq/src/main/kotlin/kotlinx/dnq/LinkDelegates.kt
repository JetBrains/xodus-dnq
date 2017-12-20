/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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

import kotlinx.dnq.link.*
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.util.XdPropertyCachedProvider
import kotlinx.dnq.util.entityType
import kotlin.reflect.KProperty1

/**
 * Directed [0..1] association
 */
fun <R : XdEntity, T : XdEntity> xdLink0_1(
        entityType: XdEntityType<T>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdToOneOptionalLink<R, T>(entityType, dbPropertyName, onDelete, onTargetDelete)
        }

/**
 * Directed [1] association
 */
fun <R : XdEntity, T : XdEntity> xdLink1(
        entityType: XdEntityType<T>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdToOneRequiredLink<R, T>(entityType, dbPropertyName, onDelete, onTargetDelete)
        }

/**
 * Directed [0..N] association
 */
fun <R : XdEntity, T : XdEntity> xdLink0_N(
        entityType: XdEntityType<T>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdToManyLink<R, T>(entityType, dbPropertyName, onDelete, onTargetDelete, required = false)
        }

/**
 * Directed [1..N] association
 */
fun <R : XdEntity, T : XdEntity> xdLink1_N(
        entityType: XdEntityType<T>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdToManyLink<R, T>(entityType, dbPropertyName, onDelete, onTargetDelete, required = true)
        }

/**
 * Undirected [0..1] association, opposite end is scalar
 */
@JvmName("xdLink0_1_opposite_single")
inline fun <R : XdEntity, reified T : XdEntity> xdLink0_1(
        oppositeLink: KProperty1<T, R?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdOneToOneOptionalLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete)
        }

/**
 * Undirected [1] association, opposite end is scalar
 */
@JvmName("xdLink1_opposite_single")
inline fun <R : XdEntity, reified T : XdEntity> xdLink1(
        oppositeLink: KProperty1<T, R?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdOneToOneRequiredLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete)
        }

/**
 * Undirected [0..N] association, opposite end is scalar
 */
@JvmName("xdLink0_N_opposite_single")
inline fun <R : XdEntity, reified T : XdEntity> xdLink0_N(
        oppositeLink: KProperty1<T, R?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdOneToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = false)
        }

/**
 * Undirected [1..N] association, opposite end is scalar
 */
@JvmName("xdLink1_N_opposite_single")
inline fun <R : XdEntity, reified T : XdEntity> xdLink1_N(
        oppositeLink: KProperty1<T, R?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdOneToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = true)
        }

/**
 * Undirected [0..1] association, opposite end is vector
 */
@JvmName("xdLink0_1_opposite_multi")
inline fun <R : XdEntity, reified T : XdEntity> xdLink0_1(
        oppositeLink: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdManyToOneOptionalLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete)
        }

/**
 * Undirected [1] association, opposite end is vector
 */
@JvmName("xdLink1_opposite_multi")
inline fun <R : XdEntity, reified T : XdEntity> xdLink1(
        oppositeLink: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdManyToOneRequiredLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete)
        }

/**
 * Undirected [0..N] association, opposite end is vector
 */
@JvmName("xdLink0_N_opposite_multi")
inline fun <R : XdEntity, reified T : XdEntity> xdLink0_N(
        oppositeLink: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdManyToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = false)
        }

/**
 * Undirected [1..N] association, opposite end is vector
 */
@JvmName("xdLink1_N_opposite_multi")
inline fun <R : XdEntity, reified T : XdEntity> xdLink1_N(
        oppositeLink: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdManyToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = true)
        }

/**
 * Parent end [0..1] of aggregation association
 */
inline fun <R : XdEntity, reified T : XdEntity> xdChild0_1(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdParentToOneOptionalChildLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

/**
 * Parent end [1] of aggregation association
 */
inline fun <R : XdEntity, reified T : XdEntity> xdChild1(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdParentToOneRequiredChildLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

/**
 * Parent end [0..N] of aggregation association
 */
inline fun <R : XdEntity, reified T : XdEntity> xdChildren0_N(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdParentToManyChildrenLink(entityTypeCompanion(), oppositeLink, dbPropertyName, required = false)
        }

/**
 * Parent end [1..N] of aggregation association
 */
inline fun <R : XdEntity, reified T : XdEntity> xdChildren1_N(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdParentToManyChildrenLink(entityTypeCompanion(), oppositeLink, dbPropertyName, required = true)
        }

/**
 * Child end of scalar aggregation association
 */
@JvmName("xdParent_opposite_single")
inline fun <R : XdEntity, reified T : XdEntity> xdParent(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdOneChildToParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

/**
 * Child end of scalar aggregation association.
 * Should be used if entity has several parent links
 */
@JvmName("xdMultiParent_opposite_single")
inline fun <R : XdEntity, reified T : XdEntity> xdMultiParent(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdOneChildToMultiParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

/**
 * Child end of vector aggregation association
 */
@JvmName("xdParent_opposite_multi")
inline fun <R : XdEntity, reified T : XdEntity> xdParent(oppositeLink: KProperty1<T, XdMutableQuery<R>>, dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdManyChildrenToParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

/**
 * Child end of vector aggregation association
 * Should be used if entity has several parent links
 */
@JvmName("xdMultiParent_opposite_multi")
inline fun <R : XdEntity, reified T : XdEntity> xdMultiParent(oppositeLink: KProperty1<T, XdMutableQuery<R>>, dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdManyChildrenToMultiParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

inline fun <reified T : XdEntity> entityTypeCompanion(): XdEntityType<T> = T::class.entityType