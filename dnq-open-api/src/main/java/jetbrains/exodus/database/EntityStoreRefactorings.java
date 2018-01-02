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

import jetbrains.exodus.entitystore.Entity;
import org.jetbrains.annotations.NotNull;

public interface EntityStoreRefactorings {

    boolean entityTypeExists(@NotNull final String entityTypeName);

    void renameEntityTypeRefactoring(@NotNull final String oldEntityTypeName, @NotNull final String newEntityTypeName);

    void deleteEntityTypeRefactoring(@NotNull final String entityTypeName);

    void deleteEntityRefactoring(@NotNull final Entity entity);

    void deleteLinksRefactoring(@NotNull final Entity entity, @NotNull final String linkName);

    void deleteLinkRefactoring(@NotNull final Entity entity, @NotNull final String linkName, @NotNull final Entity link);
}
