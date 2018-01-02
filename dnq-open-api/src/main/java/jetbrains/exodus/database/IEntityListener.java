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

public interface IEntityListener<T extends Entity> {
    void addedAsync(T added);
    void addedSync(T added);
    void addedSyncAfterConstraints(T added);
    void addedSyncBeforeConstraints(T added);
    void updatedAsync(T old, T current);
    void updatedSync(T old, T current);
    void updatedSyncAfterConstraints(T old, T current);
    void updatedSyncBeforeConstraints(T old, T current);
    void removedAsync(T removed);
    void removedSync(T removed);
    void removedSyncAfterConstraints(T added);
    void removedSyncBeforeConstraints(T removed);

    @Deprecated
    void addedSyncBeforeFlush(T added); // use addedSyncAfterConstraints
    @Deprecated
    void updatedSyncBeforeFlush(T old, T current); // use updatedSyncAfterConstraints
    @Deprecated
    void removedSyncBeforeFlush(T removed); // use removedSyncAfterConstraints
}
