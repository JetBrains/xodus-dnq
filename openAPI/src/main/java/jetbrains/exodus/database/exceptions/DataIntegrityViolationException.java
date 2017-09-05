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
package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityId;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.entitystore.EntityStoreException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class DataIntegrityViolationException extends EntityStoreException {

    protected EntityId entityId;
    protected transient Entity entity;
    protected String displayMessage;

    /**
     * @param entity entity that has incoming links
     */
    public DataIntegrityViolationException(@NotNull String message, @Nullable String displayMessage, @NotNull Entity entity) {
        this(message, displayMessage);
        this.entity = entity;
        if (entity != null) {
            this.entityId = entity.getId();
        }
    }

    protected DataIntegrityViolationException(@NotNull String message, @NotNull Entity entity,  @NotNull Throwable cause) {
        super(message, cause);
        this.entityId = entity.getId();
    }

    protected DataIntegrityViolationException(@NotNull String message, @Nullable String displayMessage) {
        super(message);
        this.displayMessage = displayMessage != null ? displayMessage : message;
    }

    protected DataIntegrityViolationException(String message) {
        this(message, null);
    }

    public boolean relatesTo(@NotNull TransientEntity entity, @Nullable Object fieldIdent) {
        return entity.getId().equals(entityId);
    }

    public EntityFieldHandler getEntityFieldHandler() {
        return entityId == null ? null : EntityFieldHandler.create(entityId, null);
    }

    public String getDisplayMessage() {
        return displayMessage;
    }

}
