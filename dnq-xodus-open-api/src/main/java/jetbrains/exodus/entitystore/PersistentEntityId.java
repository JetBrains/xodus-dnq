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

import jetbrains.exodus.ExodusException;
import org.jetbrains.annotations.NotNull;

/**
 * A purely logical {@link EntityId}: a {@code (typeId, localId)} pair that has <b>not</b> been
 * resolved against the database, so it carries no physical record id (no {@code RID}).
 *
 * <p>This is the lightweight counterpart of a resolved {@code RIDEntityId}. Use it wherever an
 * entity must be referenced by its logical id alone — e.g. {@code EntityIdSet} iteration, or
 * passing an id into the store to be resolved. Resolution happens explicitly via the store
 * (e.g. {@code requireOEntityId}); a {@code PersistentEntityId} deliberately exposes no
 * {@code asOId()}, so it can never masquerade as a resolved id with a bogus record pointer.
 *
 * <p><b>Serialization compatibility:</b> this class is a drop-in replacement for the classic Xodus
 * {@code jetbrains.exodus.entitystore.PersistentEntityId}, which consumers (e.g. Hub event-change
 * {@code Data}) durably persist via Java serialization. Blobs written before the YouTrackDB
 * migration must deserialize into this class, which pins three things: the fully-qualified class
 * name, the {@code serialVersionUID} ({@code -3875948066835180514L}), and the exact field layout —
 * {@code entityTypeId}/{@code entityLocalId} declared <i>in this class</i> with {@code Object} as
 * the superclass. Do NOT refactor these fields up into a shared base class: inheriting them would
 * silently deserialize old blobs to id {@code 0-0} (stream fields find no match and are discarded;
 * the absent-from-stream superclass defaults to zeros). The deserialized ids stay meaningful after
 * the data migration because {@code XodusToOrientDataMigrator} preserves both {@code typeId} and
 * {@code localId}.
 *
 * <p>Identity ({@code equals}/{@code hashCode}/{@code compareTo}/{@code toString}) follows the
 * universal {@code (typeId, localId)} {@link EntityId} contract — the same one independently
 * reproduced by {@code RIDEntityId}. In particular {@code equals} accepts any {@link EntityId}
 * (unlike classic Xodus, which required {@code instanceof PersistentEntityId}), keeping equality
 * symmetric with {@code RIDEntityId}. The invariant — and that the two impls' hash/ordering formulas
 * stay in lockstep — is pinned by {@code EntityIdContractTest}.
 */
public final class PersistentEntityId implements EntityId {

    private static final long serialVersionUID = -3875948066835180514L;

    private final int entityTypeId;
    private final long entityLocalId;

    public PersistentEntityId(final int entityTypeId, final long entityLocalId) {
        if (entityTypeId < 0) {
            throw new ExodusException("TypeId can't be negative: " + entityTypeId);
        }
        if (entityLocalId < 0) {
            throw new ExodusException("LocalId can't be negative: " + entityLocalId);
        }
        this.entityTypeId = entityTypeId;
        this.entityLocalId = entityLocalId;
    }

    /**
     * Copies the logical {@code (typeId, localId)} pair of the specified id, dropping any
     * resolution state.
     */
    public PersistentEntityId(@NotNull final EntityId id) {
        this(id.getTypeId(), id.getLocalId());
    }

    /**
     * Parses the {@code "typeId-localId"} representation produced by {@link #toString()}.
     * Parse-only: the result is not resolved against any database.
     *
     * <p>Throws {@link IllegalArgumentException} (or its subclass {@link NumberFormatException})
     * on malformed input, exactly like the classic Xodus implementation ({@code EntityIdCache}) —
     * consumers (e.g. Hub's {@code resolveEntityID}) catch {@code IllegalArgumentException} to map
     * a malformed id to "not found".
     */
    @NotNull
    public static PersistentEntityId toEntityId(@NotNull final CharSequence representation) {
        final String[] idParts = representation.toString().split("-");
        if (idParts.length != 2) {
            throw new IllegalArgumentException("Invalid structure of entity id: " + representation);
        }
        return new PersistentEntityId(Integer.parseInt(idParts[0]), Long.parseLong(idParts[1]));
    }

    @Override
    public int getTypeId() {
        return entityTypeId;
    }

    @Override
    public long getLocalId() {
        return entityLocalId;
    }

    @Override
    public int hashCode() {
        return (int) (entityTypeId << 20 ^ entityLocalId);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof EntityId)) return false;
        final EntityId that = (EntityId) obj;
        return entityLocalId == that.getLocalId() && entityTypeId == that.getTypeId();
    }

    @NotNull
    @Override
    public String toString() {
        return entityTypeId + "-" + entityLocalId;
    }

    @Override
    public int compareTo(@NotNull final EntityId o) {
        final int otherType = o.getTypeId();
        if (entityTypeId != otherType) return Integer.compare(entityTypeId, otherType);
        return Long.compare(entityLocalId, o.getLocalId());
    }
}
