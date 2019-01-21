/**
 * Copyright 2006 - 2019 JetBrains s.r.o.
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
 * Gets from cache or creates a new property delegate for unidirectional persistent link with `[0..1]` cardinality.
 *
 * Resulting property has type `XdTarget?`.
 * If its value is not defined in the database the property returns `null`.
 *
 * **Sample:**
 * ```
 * var supervisor by xdLink0_1(XdEmployee, "boss")
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param entityType companion object of persistent class that is an opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param onDelete defines what should happen to the entity on the opposite end when this entity is deleted:
 *        `CLEAR` (by default): nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when the entity on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link should be cleared before target entity delete,
 *        `CLEAR`: link is cleared,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
fun <XdSource : XdEntity, XdTarget : XdEntity> xdLink0_1(
        entityType: XdEntityType<XdTarget>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdToOneOptionalLink<XdSource, XdTarget>(entityType, dbPropertyName, onDelete, onTargetDelete)
        }

/**
 * Gets from cache or creates a new property delegate for unidirectional persistent link with `[1]` cardinality.
 * Xodus-DNQ checks on flush that the link points to some entity.
 *
 * Resulting property has type `XdTarget`.
 * While its value is not defined in the database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample:**
 * ```
 * var skill by xdLink1(XdSkill)
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param entityType companion object of persistent class that is an opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param onDelete defines what should happen to the entity on the opposite end when this entity is deleted:
 *        `CLEAR` (by default): nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when the entity on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link should be cleared before target entity delete,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
fun <XdSource : XdEntity, XdTarget : XdEntity> xdLink1(
        entityType: XdEntityType<XdTarget>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdToOneRequiredLink<XdSource, XdTarget>(entityType, dbPropertyName, onDelete, onTargetDelete)
        }

/**
 * Gets from cache or creates a new read-only property delegate for unidirectional persistent link
 * with `[0..N]` cardinality.
 *
 * Resulting property has type `XdMutableQuery<XdTarget>`.
 * If its value is not defined in the database the property returns `XdTarget.emptyQuery()`.
 *
 * **Sample:**
 * ```
 * val users by xdLink0_N(XdUser)
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param entityType companion object of persistent class that is an opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param onDelete defines what should happen to the entities on the opposite end when this entity is deleted:
 *        `CLEAR` (by default): nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when one of the entities on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link with the deleted entity should be removed before target entity delete,
 *        `CLEAR`: link with the deleted entity is removed,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
fun <XdSource : XdEntity, XdTarget : XdEntity> xdLink0_N(
        entityType: XdEntityType<XdTarget>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdToManyLink<XdSource, XdTarget>(entityType, dbPropertyName, onDelete, onTargetDelete, required = false)
        }

/**
 * Gets from cache or creates a new read-only property delegate for unidirectional persistent link
 * with `[1..N]` cardinality.
 * Xodus-DNQ checks on flush that the link contains at least one entity.
 *
 * Resulting property has type `XdMutableQuery<XdTarget>`.
 * If its value is not defined in the database the property returns `XdTarget.emptyQuery()`.
 *
 * **Sample:**
 * ```
 * val users by xdLink1_N(XdUser)
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param entityType companion object of persistent class that is an opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param onDelete defines what should happen to the entities on the opposite end when this entity is deleted:
 *        `CLEAR` (by default): nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when one of the entities on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link with the deleted entity should be removed before target entity delete,
 *        `CLEAR`: link with the deleted entity is removed,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
fun <XdSource : XdEntity, XdTarget : XdEntity> xdLink1_N(
        entityType: XdEntityType<XdTarget>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdToManyLink<XdSource, XdTarget>(entityType, dbPropertyName, onDelete, onTargetDelete, required = true)
        }

/**
 * Gets from cache or creates a new property delegate for bidirectional persistent link with `[0..1]` cardinality.
 * For bidirectional associations Xodus-DNQ maintains both ends of the links. For example, if there is a bidirectional
 * link between `XdUser::groups` and `XdGroup::users`, and you add some group to `user.groups.add(group)`
 * Xodus-DNQ will automatically add `user` to `group.users`.
 *
 * Resulting property has type `XdTarget?`.
 * If its value is not defined in the database the property returns `null`.
 *
 * **Sample:**
 * ```
 * val next by xdLink0_1(XdElement::prev)
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param oppositeLink reference to a property that defines the opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param dbOppositePropertyName name of the persistent property representing the opposite end of the link.
 *        If `null` (by default) then name of the `oppositeLink` is used.
 * @param onDelete defines what should happen to the entity on the opposite end when this entity is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link should be cleared before this entity delete.
 *        `CLEAR`: nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when the entity on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link should be cleared before target entity delete,
 *        `CLEAR`: link is cleared,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdLink0_1_opposite_single")
inline fun <XdSource : XdEntity, reified XdTarget : XdEntity> xdLink0_1(
        oppositeLink: KProperty1<XdTarget, XdSource?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdOneToOneOptionalLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete)
        }

/**
 * Gets from cache or creates a new property delegate for bidirectional persistent link with `[1]` cardinality.
 * Xodus-DNQ checks on flush that the link points to some entity.
 * For bidirectional associations Xodus-DNQ maintains both ends of the links. For example, if there is a bidirectional
 * link between `XdUser::groups` and `XdGroup::users`, and you add some group to `user.groups.add(group)`
 * Xodus-DNQ will automatically add `user` to `group.users`.
 *
 * Resulting property has type `XdTarget`.
 * While its value is not defined in the database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample:**
 * ```
 * val profile by xdLink1(XdProfile::user)
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param oppositeLink reference to a property that defines the opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param dbOppositePropertyName name of the persistent property representing the opposite end of the link.
 *        If `null` (by default) then name of the `oppositeLink` is used.
 * @param onDelete defines what should happen to the entity on the opposite end when this entity is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link should be cleared before this entity delete.
 *        `CLEAR`: nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when the entity on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link should be cleared before target entity delete,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdLink1_opposite_single")
inline fun <XdSource : XdEntity, reified XdTarget : XdEntity> xdLink1(
        oppositeLink: KProperty1<XdTarget, XdSource?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdOneToOneRequiredLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete)
        }

/**
 * Gets from cache or creates a new read-only property delegate for bidirectional persistent link
 * with `[0..N]` cardinality.
 * For bidirectional associations Xodus-DNQ maintains both ends of the links. For example, if there is a bidirectional
 * link between `XdUser::groups` and `XdGroup::users`, and you add some group to `user.groups.add(group)`
 * Xodus-DNQ will automatically add `user` to `group.users`.
 *
 * Resulting property has type `XdMutableQuery<XdTarget>`.
 * If its value is not defined in the database the property returns `XdTarget.emptyQuery()`.
 *
 * **Sample:**
 * ```
 * val users by xdLink0_N(XdUser::group)
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param oppositeLink reference to a property that defines the opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param dbOppositePropertyName name of the persistent property representing the opposite end of the link.
 *        If `null` (by default) then name of the `oppositeLink` is used.
 * @param onDelete defines what should happen to the entities on the opposite end when this entity is deleted:
 *        `FAIL` (by default*) --- transaction fails, i.e. association should be deleted before this entity delete.
 *        `CLEAR`: nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when one of the entities on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link with the deleted entity should be removed before target entity delete,
 *        `CLEAR`: link with the deleted entity is removed,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdLink0_N_opposite_single")
inline fun <XdSource : XdEntity, reified XdTarget : XdEntity> xdLink0_N(
        oppositeLink: KProperty1<XdTarget, XdSource?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdOneToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = false)
        }

/**
 * Gets from cache or creates a new read-only property delegate for bidirectional persistent link
 * with `[1..N]` cardinality.
 * Xodus-DNQ checks on flush that the link contains at least one entity.
 * For bidirectional associations Xodus-DNQ maintains both ends of the links. For example, if there is a bidirectional
 * link between `XdUser::groups` and `XdGroup::users`, and you add some group to `user.groups.add(group)`
 * Xodus-DNQ will automatically add `user` to `group.users`.
 *
 * Resulting property has type `XdMutableQuery<XdTarget>`.
 * While its value is not defined in database the property returns `XdTarget.emptyQuery()`.
 *
 * **Sample:**
 * ```
 * val users by xdLink1_N(XdUser::group)
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param oppositeLink reference to a property that defines the opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param dbOppositePropertyName name of the persistent property representing the opposite end of the link.
 *        If `null` (by default) then name of the `oppositeLink` is used.
 * @param onDelete defines what should happen to the entities on the opposite end when this entity is deleted:
 *        `FAIL` (by default*) --- transaction fails, i.e. association should be deleted before this entity delete.
 *        `CLEAR`: nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when one of the entities on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link with the deleted entity should be removed before target entity delete,
 *        `CLEAR`: link with the deleted entity is removed,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdLink1_N_opposite_single")
inline fun <XdSource : XdEntity, reified XdTarget : XdEntity> xdLink1_N(
        oppositeLink: KProperty1<XdTarget, XdSource?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdOneToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = true)
        }

/**
 * Gets from cache or creates a new property delegate for bidirectional persistent link with `[0..1]` cardinality.
 * For bidirectional associations Xodus-DNQ maintains both ends of the links. For example, if there is a bidirectional
 * link between `XdUser::groups` and `XdGroup::users`, and you add some group to `user.groups.add(group)`
 * Xodus-DNQ will automatically add `user` to `group.users`.
 *
 * Resulting property has type `XdTarget?`.
 * If its value is not defined in the database the property returns `null`.
 *
 * **Sample:**
 * ```
 * val group by xdLink0_1(XdGroup::users)
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param oppositeLink reference to a property that defines the opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param dbOppositePropertyName name of the persistent property representing the opposite end of the link.
 *        If `null` (by default) then name of the `oppositeLink` is used.
 * @param onDelete defines what should happen to the entity on the opposite end when this entity is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link should be cleared before this entity delete.
 *        `CLEAR`: nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when the entity on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link should be cleared before target entity delete,
 *        `CLEAR`: link is cleared,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdLink0_1_opposite_multi")
inline fun <XdSource : XdEntity, reified XdTarget : XdEntity> xdLink0_1(
        oppositeLink: KProperty1<XdTarget, XdMutableQuery<XdSource>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdManyToOneOptionalLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete)
        }

/**
 * Gets from cache or creates a new property delegate for bidirectional persistent link with `[1]` cardinality.
 * Xodus-DNQ checks on flush that the link points to some entity.
 * For bidirectional associations Xodus-DNQ maintains both ends of the links. For example, if there is a bidirectional
 * link between `XdUser::groups` and `XdGroup::users`, and you add some group to `user.groups.add(group)`
 * Xodus-DNQ will automatically add `user` to `group.users`.
 *
 * Resulting property has type `XdTarget`.
 * While its value is not defined in the database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample:**
 * ```
 * val group by xdLink1(XdGroup::users)
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param oppositeLink reference to a property that defines the opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param dbOppositePropertyName name of the persistent property representing the opposite end of the link.
 *        If `null` (by default) then name of the `oppositeLink` is used.
 * @param onDelete defines what should happen to the entity on the opposite end when this entity is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link should be cleared before this entity delete.
 *        `CLEAR`: nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when the entity on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link should be cleared before target entity delete,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdLink1_opposite_multi")
inline fun <XdSource : XdEntity, reified XdTarget : XdEntity> xdLink1(
        oppositeLink: KProperty1<XdTarget, XdMutableQuery<XdSource>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdManyToOneRequiredLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete)
        }

/**
 * Gets from cache or creates a new read-only property delegate for bidirectional persistent link
 * with `[0..N]` cardinality.
 * For bidirectional associations Xodus-DNQ maintains both ends of the links. For example, if there is a bidirectional
 * link between `XdUser::groups` and `XdGroup::users`, and you add some group to `user.groups.add(group)`
 * Xodus-DNQ will automatically add `user` to `group.users`.
 *
 * Resulting property has type `XdMutableQuery<XdTarget>`.
 * If its value is not defined in the database the property returns `XdTarget.emptyQuery()`.
 *
 * **Sample:**
 * ```
 * val users by xdLink0_N(XdUser::groups)
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param oppositeLink reference to a property that defines the opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param dbOppositePropertyName name of the persistent property representing the opposite end of the link.
 *        If `null` (by default) then name of the `oppositeLink` is used.
 * @param onDelete defines what should happen to the entities on the opposite end when this entity is deleted:
 *        `FAIL` (by default*) --- transaction fails, i.e. association should be deleted before this entity delete.
 *        `CLEAR`: nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when one of the entities on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link with the deleted entity should be removed before target entity delete,
 *        `CLEAR`: link with the deleted entity is removed,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdLink0_N_opposite_multi")
inline fun <XdSource : XdEntity, reified XdTarget : XdEntity> xdLink0_N(
        oppositeLink: KProperty1<XdTarget, XdMutableQuery<XdSource>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdManyToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = false)
        }

/**
 * Gets from cache or creates a new read-only property delegate for bidirectional persistent link
 * with `[1..N]` cardinality.
 * Xodus-DNQ checks on flush that the link contains at least one entity.
 * For bidirectional associations Xodus-DNQ maintains both ends of the links. For example, if there is a bidirectional
 * link between `XdUser::groups` and `XdGroup::users`, and you add some group to `user.groups.add(group)`
 * Xodus-DNQ will automatically add `user` to `group.users`.
 *
 * Resulting property has type `XdMutableQuery<XdTarget>`.
 * While its value is not defined in database the property returns `XdTarget.emptyQuery()`.
 *
 * **Sample:**
 * ```
 * val users by xdLink1_N(XdUser::groups)
 * ```
 *
 * @param XdSource type of link source.
 * @param XdTarget type of link target.
 * @param oppositeLink reference to a property that defines the opposite end of the link.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @param dbOppositePropertyName name of the persistent property representing the opposite end of the link.
 *        If `null` (by default) then name of the `oppositeLink` is used.
 * @param onDelete defines what should happen to the entities on the opposite end when this entity is deleted:
 *        `FAIL` (by default*) --- transaction fails, i.e. association should be deleted before this entity delete.
 *        `CLEAR`: nothing,
 *        `CASCADE`: entity on the opposite end is deleted as well.
 * @param onTargetDelete defines what should happen to this entity when one of the entities on the opposite end is deleted:
 *        `FAIL` (by default): transaction fails, i.e. link with the deleted entity should be removed before target entity delete,
 *        `CLEAR`: link with the deleted entity is removed,
 *        `CASCADE`: this entity is deleted as well.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdLink1_N_opposite_multi")
inline fun <XdSource : XdEntity, reified XdTarget : XdEntity> xdLink1_N(
        oppositeLink: KProperty1<XdTarget, XdMutableQuery<XdSource>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL) =
        XdPropertyCachedProvider {
            XdManyToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = true)
        }

/**
 * Gets from cache or creates a new property delegate for parent end of aggregation association
 * with `[0..1]` cardinality.
 * Aggregations or parent-child association are auxiliary type of links with some predefined behavior.
 * 1. If persistent class of an entity has at least one parent link defined, it is considered to be a child entity
 *    and it should have exactly one parent on flush.
 * 2. On parent delete all its children are deleted as well.
 *
 * Resulting property has type `XdChild?`.
 * If its value is not defined in the database the property returns `null`.
 *
 * **Sample:**
 * ```
 * val profile by xdChild0_1(XdUser::profile)
 * ```
 *
 * @param XdParent type of link source.
 * @param XdChild type of link target.
 * @param oppositeLink property reference to a child end of the association.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
inline fun <XdParent : XdEntity, reified XdChild : XdEntity> xdChild0_1(
        oppositeLink: KProperty1<XdChild, XdParent?>,
        dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdParentToOneOptionalChildLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

/**
 * Gets from cache or creates a new property delegate for parent end of aggregation association
 * with `[1]` cardinality.
 * Xodus-DNQ checks on flush that the link points to some entity.
 * Aggregations or parent-child association are auxiliary type of links with some predefined behavior.
 * 1. If persistent class of an entity has at least one parent link defined, it is considered to be a child entity
 *    and it should have exactly one parent on flush.
 * 2. On parent delete all its children are deleted as well.
 *
 * Resulting property has type `XdChild`.
 * While its value is not defined in the database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample:**
 * ```
 * val profile by xdChild1(XdUser::profile)
 * ```
 *
 * @param XdParent type of link source.
 * @param XdChild type of link target.
 * @param oppositeLink property reference to a child end of the association.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
inline fun <XdParent : XdEntity, reified XdChild : XdEntity> xdChild1(
        oppositeLink: KProperty1<XdChild, XdParent?>,
        dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdParentToOneRequiredChildLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

/**
 * Gets from cache or creates a new read-only property delegate for parent end of aggregation association
 * with `[0..N]` cardinality.
 * Aggregations or parent-child association are auxiliary type of links with some predefined behavior.
 * 1. If persistent class of an entity has at least one parent link defined, it is considered to be a child entity
 *    and it should have exactly one parent on flush.
 * 2. On parent delete all its children are deleted as well.
 *
 * Resulting property has type `XdMutableQuery<XdTarget>`.
 * If its value is not defined in the database the property returns `XdTarget.emptyQuery()`.
 *
 * **Sample:**
 * ```
 * val subGroups by xdChildren0_N(XdGroup::parentGroup)
 * ```
 *
 * @param XdParent type of link source.
 * @param XdChild type of link target.
 * @param oppositeLink property reference to a child end of the association.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
inline fun <XdParent : XdEntity, reified XdChild : XdEntity> xdChildren0_N(
        oppositeLink: KProperty1<XdChild, XdParent?>,
        dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdParentToManyChildrenLink(entityTypeCompanion(), oppositeLink, dbPropertyName, required = false)
        }

/**
 * Gets from cache or creates a new read-only property delegate for parent end of aggregation association
 * with `[1..N]` cardinality.
 * Xodus-DNQ checks on flush that the link contains at least one entity.
 * Aggregations or parent-child association are auxiliary type of links with some predefined behavior.
 * 1. If persistent class of an entity has at least one parent link defined, it is considered to be a child entity
 *    and it should have exactly one parent on flush.
 * 2. On parent delete all its children are deleted as well.
 *
 * Resulting property has type `XdMutableQuery<XdTarget>`.
 * While its value is not defined in database the property returns `XdTarget.emptyQuery()`.
 *
 * **Sample:**
 * ```
 * val contacts by xdChildren1_N(XdContact::user)
 * ```
 *
 * @param XdParent type of link source.
 * @param XdChild type of link target.
 * @param oppositeLink property reference to a child end of the association.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
inline fun <XdParent : XdEntity, reified XdChild : XdEntity> xdChildren1_N(
        oppositeLink: KProperty1<XdChild, XdParent?>,
        dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdParentToManyChildrenLink(entityTypeCompanion(), oppositeLink, dbPropertyName, required = true)
        }

/**
 * Gets from cache or creates a new property delegate for child end of aggregation association when
 * only one parent link is defined for persistent class.
 * Xodus-DNQ checks on flush that the link points to some entity.
 * Aggregations or parent-child association are auxiliary type of links with some predefined behavior.
 * 1. If persistent class of an entity has at least one parent link defined, it is considered to be a child entity
 *    and it should have exactly one parent on flush.
 * 2. On parent delete all its children are deleted as well.
 *
 * Resulting property has type `XdParent`.
 * While its value is not defined in the database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample:**
 * ```
 * val user by xdParent(XdUser::contacts)
 * ```
 *
 * @param XdChild type of link source.
 * @param XdParent type of link target.
 * @param oppositeLink property reference to a parent end of the association.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdParent_opposite_single")
inline fun <XdChild : XdEntity, reified XdParent : XdEntity> xdParent(
        oppositeLink: KProperty1<XdParent, XdChild?>,
        dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdOneChildToParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

/**
 * Gets from cache or creates a new property delegate for child end of aggregation association when
 * multiple parent links are defined for persistent class.
 * Xodus-DNQ checks on flush that this entity has exactly one parent link defined.
 * Aggregations or parent-child association are auxiliary type of links with some predefined behavior.
 * 1. If persistent class of an entity has at least one parent link defined, it is considered to be a child entity
 *    and it should have exactly one parent on flush.
 * 2. On parent delete all its children are deleted as well.
 *
 * Resulting property has type `XdParent?`.
 * If its value is not defined in the database the property returns `null`.
 *
 * **Sample:**
 * ```
 * val parentGroup by xdMultiParent(XdGroup::subGroups)
 * val parentOfRootGroup by xdMultiParent(XdRoot::rootGroup)
 * ```
 *
 * @param XdChild type of link source.
 * @param XdParent type of link target.
 * @param oppositeLink property reference to a parent end of the association.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdMultiParent_opposite_single")
inline fun <XdChild : XdEntity, reified XdParent : XdEntity> xdMultiParent(
        oppositeLink: KProperty1<XdParent, XdChild?>,
        dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdOneChildToMultiParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

/**
 * Gets from cache or creates a new property delegate for child end of aggregation association when
 * only one parent link is defined for persistent class.
 * Xodus-DNQ checks on flush that the link points to some entity.
 * Aggregations or parent-child association are auxiliary type of links with some predefined behavior.
 * 1. If persistent class of an entity has at least one parent link defined, it is considered to be a child entity
 *    and it should have exactly one parent on flush.
 * 2. On parent delete all its children are deleted as well.
 *
 * Resulting property has type `XdParent`.
 * While its value is not defined in the database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample:**
 * ```
 * val user by xdParent(XdUser::contacts)
 * ```
 *
 * @param XdChild type of link source.
 * @param XdParent type of link target.
 * @param oppositeLink property reference to a parent end of the association.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdParent_opposite_multi")
inline fun <XdChild : XdEntity, reified XdParent : XdEntity> xdParent(
        oppositeLink: KProperty1<XdParent, XdMutableQuery<XdChild>>,
        dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdManyChildrenToParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

/**
 * Gets from cache or creates a new property delegate for child end of aggregation association when
 * multiple parent links are defined for persistent class.
 * Xodus-DNQ checks on flush that this entity has exactly one parent link defined.
 * Aggregations or parent-child association are auxiliary type of links with some predefined behavior.
 * 1. If persistent class of an entity has at least one parent link defined, it is considered to be a child entity
 *    and it should have exactly one parent on flush.
 * 2. On parent delete all its children are deleted as well.
 *
 * Resulting property has type `XdParent?`.
 * If its value is not defined in the database the property returns `null`.
 *
 * **Sample:**
 * ```
 * val parentGroup by xdMultiParent(XdGroup::subGroups)
 * val parentOfRootGroup by xdMultiParent(XdRoot::rootGroup)
 * ```
 *
 * @param XdChild type of link source.
 * @param XdParent type of link target.
 * @param oppositeLink property reference to a parent end of the association.
 * @param dbPropertyName name of persistent link in database. If `null` (by default) then name of the related
 *        Kotlin-property is used.
 * @return property delegate to access Xodus database persistent link using Kotlin-property.
 */
@JvmName("xdMultiParent_opposite_multi")
inline fun <XdChild : XdEntity, reified XdParent : XdEntity> xdMultiParent(
        oppositeLink: KProperty1<XdParent, XdMutableQuery<XdChild>>,
        dbPropertyName: String? = null) =
        XdPropertyCachedProvider {
            XdManyChildrenToMultiParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
        }

inline fun <reified T : XdEntity> entityTypeCompanion(): XdEntityType<T> = T::class.entityType