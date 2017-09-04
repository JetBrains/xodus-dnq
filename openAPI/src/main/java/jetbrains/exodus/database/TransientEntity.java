package jetbrains.exodus.database;

import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityIterable;
import jetbrains.exodus.entitystore.EntityStore;
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

    boolean hasChanges(String property);

    boolean hasChangesExcepting(String[] properties);

    EntityIterable getAddedLinks(String name);

    EntityIterable getRemovedLinks(String name);

    EntityIterable getAddedLinks(Set<String> linkNames);

    EntityIterable getRemovedLinks(Set<String> linkNames);

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

    Entity getParent();
}
