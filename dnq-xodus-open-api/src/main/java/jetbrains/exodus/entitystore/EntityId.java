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
package jetbrains.exodus.entitystore;

import org.jetbrains.annotations.NotNull;

/**
 * Each entity id consists of two parts: {@linkplain #getTypeId() id of the entity type} and
 * {@linkplain #getLocalId() local id} within entity type. So the number of entity types is bounded by
 * {@code Integer.MAX_VALUE}, and the number of entities of arbitrary entity type is bounded by {@code Long.MAX_VALUE}.
 *
 * <p>Unlike the classic Xodus interface, {@code EntityId} is deliberately <b>not</b>
 * {@link java.io.Serializable}: a resolved id ({@code RIDEntityId}) carries YouTrackDB-specific
 * state — the physical record id and schema class name — that must never leak into a Java-serialized
 * blob (its stream format is not stable, and some {@code RID} implementations are not serializable
 * at all). Only the logical {@link PersistentEntityId} is serializable; convert via
 * {@code new PersistentEntityId(id)} before putting an id into a serialized graph.
 *
 * @see Entity#getId()
 * @see Entity#toIdString()
 * @see StoreTransaction#toEntityId(String)
 */
public interface EntityId extends Comparable<EntityId> {

    /**
     * @return id of entity type
     */
    int getTypeId();

    /**
     * @return id of entity inside of entity type
     */
    long getLocalId();

    /**
     * @return string representation
     * @see Entity#toIdString()
     * @see StoreTransaction#toEntityId(String)
     */
    @NotNull
    String toString();
}
