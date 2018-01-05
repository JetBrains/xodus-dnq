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

import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityIterable;
import jetbrains.exodus.entitystore.PersistentEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

public interface TransientEntity extends Entity {

    @NotNull
    TransientEntityStore getStore();

    boolean isNew();

    boolean isSaved();

    boolean isRemoved();

    boolean isReadonly();

    boolean isWrapper();

    /**
     * @return underlying persistent entity
     */
    @NotNull
    PersistentEntity getPersistentEntity();

    /**
     * Gets incoming links to entity.
     *
     * @return list of pairs of link name and an entities which is linked with the entity being deleted.
     */
    @NotNull
    List<Pair<String, EntityIterable>> getIncomingLinks();

    long getLinksSize(@NotNull final String linkName);

    boolean hasChanges();

    boolean hasChanges(@NotNull String property);

    boolean hasChangesExcepting(@NotNull String[] properties);

    EntityIterable getAddedLinks(@NotNull String name);

    EntityIterable getRemovedLinks(@NotNull String name);

    EntityIterable getAddedLinks(@NotNull Set<String> linkNames);

    EntityIterable getRemovedLinks(@NotNull Set<String> linkNames);

    @NotNull
    String getDebugPresentation();

    @Nullable
    Comparable getPropertyOldValue(@NotNull final String propertyName);

    void setToOne(@NotNull String linkName, @Nullable Entity target);

    void setManyToOne(@NotNull String manyToOneLinkName, @NotNull String oneToManyLinkName, @Nullable Entity one);

    void clearOneToMany(@NotNull String manyToOneLinkName, @NotNull String oneToManyLinkName);

    void createManyToMany(@NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName, @NotNull Entity e2);

    void clearManyToMany(@NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName);

    void setOneToOne(@NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName, @Nullable Entity e2);

    void removeOneToMany(@NotNull String manyToOneLinkName, @NotNull String oneToManyLinkName, @NotNull Entity many);

    void removeFromParent(@NotNull String parentToChildLinkName, @NotNull String childToParentLinkName);

    void removeChild(@NotNull String parentToChildLinkName, @NotNull String childToParentLinkName);

    void setChild(@NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @NotNull Entity child);

    void clearChildren(@NotNull String parentToChildLinkName);

    void addChild(@NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @NotNull Entity child);

    @Nullable
    Entity getParent();
}
