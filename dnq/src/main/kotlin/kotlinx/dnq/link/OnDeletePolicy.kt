/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package kotlinx.dnq.link

import jetbrains.exodus.entitystore.Entity

/**
 * Defines what should happen on transaction flush if this or target entity is deleted but link still points to it.
 */
sealed class OnDeletePolicy {
    /**
     * Fail transaction if entity is deleted but link still points to it.
     */
    object FAIL : OnDeletePolicy()

    /**
     * Clear link to deleted entity.
     */
    object CLEAR : OnDeletePolicy()

    /**
     * If entity is delete and link still exists, then delete entity on the opposite link end as well.
     */
    object CASCADE : OnDeletePolicy()

    /**
     * Fail transaction with a custom message if entity is deleted but link still points to it.
     * One message per entity type.
     */
    class FAIL_PER_TYPE(val message: (linkedEntities: List<Entity>, hasMore: Boolean) -> String) : OnDeletePolicy()

    /**
     * Fail transaction with a custom message if entity is deleted but link still points to it.
     * One message per entity.
     */
    class FAIL_PER_ENTITY(val message: (entity: Entity) -> String) : OnDeletePolicy()
}