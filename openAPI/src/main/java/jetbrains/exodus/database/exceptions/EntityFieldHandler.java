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

import jetbrains.exodus.entitystore.EntityId;
import org.jetbrains.annotations.NotNull;

public class EntityFieldHandler {

    private EntityId entityId;
    private String fieldName;

    private EntityFieldHandler(@NotNull EntityId entityId, String fieldName) {
        this.entityId = entityId;
        this.fieldName = fieldName;
    }

    public EntityId getEntityId() {
        return entityId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public int hashCode() {
        return fieldName == null ? entityId.hashCode() : (entityId.hashCode() ^ fieldName.hashCode());
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof EntityFieldHandler)) {
            return false;
        }

        return ((EntityFieldHandler) obj).entityId.equals(this.entityId) &&
                (((EntityFieldHandler) obj).fieldName != null &&
                        ((EntityFieldHandler) obj).fieldName.equals(this.fieldName));
    }

    public static EntityFieldHandler create(@NotNull EntityId entityId, String fieldName) {
        return new EntityFieldHandler(entityId, fieldName);
    }

}
