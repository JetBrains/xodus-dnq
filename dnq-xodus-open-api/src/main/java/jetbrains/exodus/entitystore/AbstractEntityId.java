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
 * Shared base for {@link EntityId} implementations. An entity id is identified solely by its
 * {@code (typeId, localId)} pair, so {@code equals}, {@code hashCode}, {@code compareTo} and
 * {@code toString} are defined here once and inherited by every concrete id — the resolved
 * {@code RIDEntityId} (which additionally carries a physical record id) or an {@link AbsentEntityId}.
 * The one deliberate exception is {@link PersistentEntityId}, which must keep the exact field layout
 * of the classic Xodus class for Java-serialization compatibility and therefore duplicates this
 * contract instead of inheriting it. This guarantees that ids for the same logical entity are
 * interchangeable in equality checks and hash-based collections regardless of their concrete type.
 * The invariant is pinned by {@code EntityIdContractTest}.
 *
 * <p>Both parts must be non-negative; the constructor rejects negative values, so a malformed id is
 * caught at construction rather than surfacing later as a phantom mismatch.
 *
 * <p>{@code equals}, {@code hashCode}, {@code compareTo} and {@code toString} are {@code final}: the
 * {@code (typeId, localId)} identity contract is not up for redefinition by subclasses. Subclasses
 * only add resolution-specific behavior (e.g. {@code RIDEntityId.asOId()}).
 */
public abstract class AbstractEntityId implements EntityId {

    private static final long serialVersionUID = 1L;

    private final int typeId;
    private final long localId;

    protected AbstractEntityId(final int typeId, final long localId) {
        if (typeId < 0) {
            throw new ExodusException("TypeId can't be negative: " + typeId);
        }
        if (localId < 0) {
            throw new ExodusException("LocalId can't be negative: " + localId);
        }
        this.typeId = typeId;
        this.localId = localId;
    }

    @Override
    public final int getTypeId() {
        return typeId;
    }

    @Override
    public final long getLocalId() {
        return localId;
    }

    @Override
    public final int hashCode() {
        return (int) (typeId << 20 ^ localId);
    }

    @Override
    public final boolean equals(final Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof EntityId)) return false;
        final EntityId that = (EntityId) obj;
        return localId == that.getLocalId() && typeId == that.getTypeId();
    }

    @NotNull
    @Override
    public final String toString() {
        return typeId + "-" + localId;
    }

    @Override
    public final int compareTo(@NotNull final EntityId o) {
        final int otherType = o.getTypeId();
        if (typeId != otherType) return Integer.compare(typeId, otherType);
        return Long.compare(localId, o.getLocalId());
    }
}
