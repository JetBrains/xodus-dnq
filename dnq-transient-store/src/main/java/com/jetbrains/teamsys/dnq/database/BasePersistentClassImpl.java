/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.exceptions.CantRemoveEntityException;
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException;
import jetbrains.exodus.entitystore.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.Callable;

public abstract class BasePersistentClassImpl implements Runnable {
    @Nullable
    protected Map<String, Iterable<PropertyConstraint>> propertyConstraints;
    protected TransientEntityStore entityStore;

    protected BasePersistentClassImpl() {
    }

    protected Entity _constructor(@NotNull final String _entityType_) {
        return getEntityStore().getThreadSession().newEntity(_entityType_);
    }

    public boolean isPropertyRequired(String name, Entity entity) {
        return false;
    }

    public TransientEntityStore getEntityStore() {
        return entityStore;
    }

    public void setEntityStore(TransientEntityStore entityStore) {
        this.entityStore = entityStore;
    }

    @NotNull
    public Map<String, Iterable<PropertyConstraint>> getPropertyConstraints() {
        return propertyConstraints != null ? propertyConstraints : Collections.<String, Iterable<PropertyConstraint>>emptyMap();
    }

    protected Map<String, Callable<String>> getPropertyDisplayNames() {
        return null;
    }

    @NotNull
    public String getPropertyDisplayName(String name) {
        final Map<String, Callable<String>> propertyDisplayNames = getPropertyDisplayNames();
        final Callable<String> displayName = propertyDisplayNames == null ? null : propertyDisplayNames.get(name);
        try {
            return displayName == null ? name : displayName.call();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public void destructor(Entity entity) {
    }

    public void executeBeforeFlushTrigger(Entity entity) {
    }

    public boolean evaluateSaveHistoryCondition(Entity entity) {
        return false;
    }

    public void saveHistoryCallback(Entity entity) {
    }

    public DataIntegrityViolationException createIncomingLinksException(List<IncomingLinkViolation> linkViolations, final Entity entity) {
        List<Collection<String>> linkDescriptions = new ArrayList<Collection<String>>();
        for (IncomingLinkViolation violation : linkViolations) {
            linkDescriptions.add(violation.getDescription());
        }
        final String displayName = getDisplayName(entity);
        final String displayMessage = "Could not delete " + displayName + ", because it is referenced";
        return new CantRemoveEntityException(entity, displayMessage, displayName, linkDescriptions);
    }

    public IncomingLinkViolation createIncomingLinkViolation(String linkName) {
        return new IncomingLinkViolation(linkName);
    }

    public String getDisplayName(final Entity entity) {
        return toString(entity);
    }

    public String toString(Entity entity) {
        if (entity instanceof TransientEntity) return ((TransientEntity) entity).getDebugPresentation();
        return entity.toString();
    }

    public static <T> Set<T> buildSet(final T[] data) {
        final Set<T> result = new HashSet<T>(data.length);
        for (final T t : data) {
            result.add(t);
        }
        return result;
    }

}
