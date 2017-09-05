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
package jetbrains.exodus.database;

import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityStore;
import jetbrains.exodus.query.QueryEngine;
import jetbrains.exodus.query.metadata.ModelMetaData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Allows to suspend and resume session.
 */
public interface TransientEntityStore extends EntityStore, EntityStoreRefactorings {

    EntityStore getPersistentStore();

    @NotNull
    @Override
    TransientStoreSession beginReadonlyTransaction();

    TransientStoreSession beginSession();

    @Nullable
    TransientStoreSession getThreadSession();

    @Nullable
    TransientStoreSession suspendThreadSession();

    /**
     * Resumes previously suspened session
     *
     * @param session
     */
    void resumeSession(TransientStoreSession session);

    void setModelMetaData(final ModelMetaData modelMetaData);

    @Nullable
    ModelMetaData getModelMetaData();

    void addListener(TransientStoreSessionListener listener);

    /**
     * Adds listener with a priority.
     * The higher priority the earlier listener will be visited by the TransientEntityStoreImpl.forAllListeners().
     */
    void addListener(TransientStoreSessionListener listener, int priority);

    void removeListener(TransientStoreSessionListener listener);

    QueryEngine getQueryEngine();

    Entity getCachedEnumValue(@NotNull final String className, @NotNull final String propName);

    boolean isOpen();

    IEventsMultiplexer getEventsMultiplexer();

}
