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
package jetbrains.exodus.database;

import jetbrains.exodus.database.exceptions.DataIntegrityViolationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface TransientStoreSessionListener {

    /**
     * Called on session flush, only if were changes. Thread session is still available here, but it moved to last database root.
     *
     * @param session
     * @param changedEntities
     */
    void flushed(@NotNull TransientStoreSession session, @Nullable Set<TransientEntityChange> changedEntities);

    /**
     * Before commit or flush, only if were changes.
     * @param session
     * @param changedEntities
     */
    void beforeFlushBeforeConstraints(@NotNull TransientStoreSession session, @Nullable Set<TransientEntityChange> changedEntities);

    /**
     * Before commit or flush, only if were changes.
     * Is not allowed to have side effects, i.e. make database changes.
     *
     * @param session
     * @param changedEntities
     */
    @Deprecated
    void beforeFlushAfterConstraints(@NotNull TransientStoreSession session, @Nullable Set<TransientEntityChange> changedEntities);

    /**
     * After constraints if check is failed
     * Is not allowed to have side effects, i.e. make database changes.
     *
     * @param session
     * @param exceptions
     */
    void afterConstraintsFail(@NotNull TransientStoreSession session, @NotNull Set<DataIntegrityViolationException> exceptions);

}
