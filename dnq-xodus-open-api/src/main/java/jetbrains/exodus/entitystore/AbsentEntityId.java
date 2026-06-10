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

/**
 * The result of resolving an id that was looked up and <b>found to be absent</b> from the database —
 * the entity does not (or no longer) exists. It preserves the real logical {@code (typeId, localId)}
 * it was asked about (no {@code -1} sentinels).
 *
 * <p>Unlike a resolved id, an {@code AbsentEntityId} is deliberately <b>not</b> a {@code YTDBEntityId}:
 * it has no physical record id and exposes no {@code asOId()}, so it cannot be mistaken for something
 * that points at a record. It is distinct from {@link PersistentEntityId}, which means "not looked up
 * yet" rather than "looked up and not there"; the store treats an {@code AbsentEntityId} as known to
 * be absent and does not re-query for it.
 *
 * <p>Produced only where the contract must hand back an id for a non-existent representation (notably
 * {@code StoreTransaction.toEntityId}). Resolution primitives signal absence out-of-band (a {@code null}
 * result, or a thrown {@code EntityRemovedInDatabaseException}) rather than returning this type.
 *
 * <p>Identity ({@code equals}/{@code hashCode}/{@code compareTo}/{@code toString}) and the
 * non-negative {@code (typeId, localId)} invariant are inherited from {@link AbstractEntityId},
 * consistent with every other {@link EntityId} implementation — see {@code EntityIdContractTest}.
 */
public final class AbsentEntityId extends AbstractEntityId {

    private static final long serialVersionUID = 1L;

    public AbsentEntityId(final int typeId, final long localId) {
        super(typeId, localId);
    }
}
