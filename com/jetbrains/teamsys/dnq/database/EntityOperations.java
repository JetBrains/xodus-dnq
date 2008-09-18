package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.impl.iterate.EntityIterableBase;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import com.sleepycat.je.DatabaseException;
import jetbrains.mps.baseLanguage.ext.collections.internal.query.ISelector;
import jetbrains.mps.baseLanguage.ext.collections.internal.query.SequenceOperations;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

// TODO: move this class to the associations semantics package
public class EntityOperations {

  private static final Log log = LogFactory.getLog(EntityOperations.class);

  private EntityOperations() {
  }

  public static void remove(@NotNull Entity e) {
    remove(e, false);
  }

  static void remove(@NotNull Entity e, boolean skipEntityRemovedByYouException) {
    if (((TransientEntity) e).isRemoved() && skipEntityRemovedByYouException) {
      return;
    }

    e = TransientStoreUtil.reattach((TransientEntity) e);
    TransientEntityStore store = (TransientEntityStore) e.getStore();

    ModelMetaData md = store.getModelMetaData();
    if (md != null) {
      // cascade delete
      EntityMetaData emd = md.getEntityMetaData(((TransientEntity) e).getRealType());
      if (emd != null) {
        // call destructors starting with it and continuing with super class destructor up to root super class
        executeDestructors(e, md);

        // remove associations and cascade delete 
        ConstraintsUtil.processOnDeleteConstraints(e, emd);
      }
    }

    // delete itself
    e.delete();
  }

  private static void executeDestructors(@NotNull Entity e, @NotNull ModelMetaData md) {
    EntityMetaData emd = md.getEntityMetaData(((TransientEntity) e).getRealType());

    while (emd != null) {
      DestructorRef descructor = emd.getDestructor();
      if (descructor != null) {
        descructor.execute(e);
      }

      final String superType = emd.getSuperType();
      emd = superType == null ? null : md.getEntityMetaData(superType);
    }
  }

  public static List<Entity> getHistory(@NotNull Entity e) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    return e.getHistory();
  }

  public static int getVersion(@NotNull Entity e) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    return e.getVersion();
  }

  public static Entity getPreviousVersion(@NotNull Entity e) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    return e.getPreviousVersion();
  }

  public static Entity getNextVersion(@NotNull Entity e) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    return e.getNextVersion();
  }

  public static boolean equals(Entity e1, Object e2) {
    if (e1 == null && e2 == null) {
      return true;
    }

    if (e1 == e2) {
      return true;
    }

    if (e1 == null || !(e2 instanceof Entity)) {
      return false;
    }

    e1 = TransientStoreUtil.reattach((TransientEntity) e1);
    e2 = TransientStoreUtil.reattach((TransientEntity) e2);

    return e1.equals(e2);
  }

  @Nullable
  public static Entity cast(@Nullable Object e, @NotNull String toTypeDiscriminator) throws ClassCastException {
    if (e == null) {
      return null;
    }

    if (!(e instanceof Entity)) {
      throw new ClassCastException("Can't cast from [" + e.getClass().getName() + "] to [" + toTypeDiscriminator + "]");
    }

    if (instanceOfDiscriminator(e, toTypeDiscriminator)) {
      return (Entity) e;
    } else {
      throw new ClassCastException("Can't cast from to [" + toTypeDiscriminator + "]");
    }
  }

  public static boolean instanceOfType(@Nullable Object e, @NotNull String toType) {
    if (e == null || !(e instanceof Entity)) {
      return false;
    }

    return toType.equals(((Entity) e).getType());
  }

  public static boolean instanceOfDiscriminator(@Nullable Object e, @NotNull String toTypeDiscriminator) {
    if (e == null || !(e instanceof Entity)) {
      return false;
    }

    String fromTypeDiscriminator = (String) PrimitiveAssociationSemantics.get((Entity) e, TransientEntity.__DISCRIMINATOR__, null);

    if (fromTypeDiscriminator == null) {
      throw new IllegalArgumentException("fromTypeDiscriminator can't be null if entity is not null.");
    }

    if (fromTypeDiscriminator.startsWith(toTypeDiscriminator)) {
      return true;
    }

    return false;
  }

  public static Entity as(@Nullable Object e, @NotNull String toType) {
    Entity result = null;
    if (instanceOfType(e, toType)) {
      result = (Entity) e;
    }
    return result;
  }

  public static Entity asDiscriminator(@Nullable Object e, @NotNull String toTypeDiscriminator) {
    Entity result = null;
    if (instanceOfDiscriminator(e, toTypeDiscriminator)) {
      result = (Entity) e;
    }
    return result;
  }

  /**
   * Slow method! Use with care.
   *
   * @param entities
   * @param i
   * @return
   * @deprecated slow method. for testcases only.
   */
  public static Entity getElement(@NotNull Iterable<Entity> entities, int i) {
    if (log.isWarnEnabled()) {
      log.warn("Slow method getElementOfMultiple() was called!");
    }

    //TODO: use skip if input is EntityIterable

    int j = 0;

    for (Entity e : entities) {
      if (i == j++) {
        return e;
      }
    }

    throw new IllegalArgumentException("Out of bounds: " + i);
  }

  public static int getSize(Iterable<Entity> input) {
    if (input instanceof EntityIterable) {
      return (int) ((EntityIterable) input).size();
    }

    if(input instanceof Collection) {
      return ((Collection<Entity>)input).size();
    }

    return SequenceOperations.getSize(input);
  }

  public static int count(Iterable<Entity> input) {
    if (input instanceof EntityIterable) {
      return (int) ((EntityIterable) input).count();
    }

    if (log.isDebugEnabled()) {
      log.debug("Brute force calculation of count!", new Exception("Brute force calculation of count!"));
    }

    if(input instanceof Collection) {
      return ((Collection<Entity>)input).size();
    }

    return SequenceOperations.count(input);
  }

  public static Iterable<Entity> skip(final Iterable<Entity> input, final int elementsToSkip) {
    if (input instanceof EntityIterable) {
      return ((EntityIterable) input).skip(elementsToSkip);
    }

    return SequenceOperations.skip(input, elementsToSkip);
  }

  public static Iterable<Entity> sort(@NotNull final TransientStoreSession session,
                                      @NotNull final String entityType,
                                      @NotNull final String propertyName,
                                      @Nullable final Iterable<Entity> source,
                                      @NotNull final Comparator<Entity> comparator,
                                      final boolean ascending) {
    // for getAll("") particularly
    if (source == null) {
      return session.sort(entityType, propertyName, ascending);
    }
    // for BerkeleyDb entity iterables and PersistentEntityIterableWrapper
    if (source instanceof EntityIterable && !(source instanceof TransientEntityIterable)) {
      final EntityIterable it = ((EntityIterable) source).getSource();
      // sort by index if the index is already in-memory or if the source iterable is known to be enough large
      if (isCached(session, session.findWithProp(entityType, propertyName).getSource(), true) || it.count() > 1000) {
        return session.sort(entityType, propertyName, it, ascending);
      }
      return SequenceOperations.sort(session.createPersistentEntityIterableWrapper(it), comparator, ascending);
    }
    // for TransientEntityIterable and other Iterable<Entity> instances
    return SequenceOperations.sort(source, comparator, ascending);
  }

  public static Iterable<Entity> sort(@NotNull final TransientStoreSession session,
                                      @NotNull final String enumType,
                                      @NotNull final String propertyName,
                                      @NotNull final String entityType,
                                      @NotNull final String linkName,
                                      @Nullable final Iterable<Entity> source,
                                      @NotNull final Comparator<Entity> comparator,
                                      final boolean ascending) {
    if (source instanceof EntityIterable && !(source instanceof TransientEntityIterable)) {
      final EntityIterable it = ((EntityIterable) source).getSource();
      final long enumCount = session.getAll(enumType).size();
      final long itCount = it.size();
      if (enumCount < 40 && (itCount > enumCount || itCount < 0)) {
        EntityIterable result = null;
        for (final Entity sortedEnum : session.sort(enumType, propertyName, ascending)) {
          final EntityIterable equal = session.findLinks(entityType, sortedEnum, linkName).getSource().intersect(it);
          if (result == null) {
            result = equal;
          } else {
            result = result.getSource().concat(equal);
          }
        }
        assert result != null;
        return session.createPersistentEntityIterableWrapper(result);
      }
    }
    return SequenceOperations.sort(source, comparator, ascending);
  }

  public static Iterable<Entity> distinct(@NotNull final TransientStoreSession session,
                                          @NotNull final Iterable<Entity> source) {
    if (source instanceof EntityIterable && !(source instanceof TransientEntityIterable)) {
      return session.distinct(((EntityIterable) source).getSource());
    }
    // for TransientEntityIterable and other Iterable<Entity> instances
    return SequenceOperations.distinct(source);
  }

  public static Iterable<Entity> selectDistinct(@NotNull final TransientStoreSession session,
                                                @NotNull final Iterable<Entity> source,
                                                @NotNull final String linkName) {
    if (source instanceof EntityIterable && !(source instanceof TransientEntityIterable)) {
      return session.selectDistinct(((EntityIterable) source).getSource(), linkName);
    }
    // for TransientEntityIterable and other Iterable<Entity> instances
    return SequenceOperations.distinct(SequenceOperations.select(source, new ISelector<Entity, Entity>() {
      public Entity select(Entity input) {
        return AssociationSemantics.getToOne(input, linkName);
      }
    }));
  }

  public static Iterable<Entity> intersect(@NotNull final TransientStoreSession session,
                                           @NotNull final Iterable<Entity> left,
                                           @NotNull final Iterable<Entity> right) {
    if (left instanceof PersistentEntityIterableWrapper && right instanceof PersistentEntityIterableWrapper) {
      return session.createPersistentEntityIterableWrapper(
              ((EntityIterable) left).getSource().intersect(((EntityIterable) right).getSource()));
    }
    return SequenceOperations.intersect(left, right);
  }

  public static Iterable<Entity> union(@NotNull final TransientStoreSession session,
                                       @NotNull final Iterable<Entity> left,
                                       @NotNull final Iterable<Entity> right) {
    if (left instanceof PersistentEntityIterableWrapper && right instanceof PersistentEntityIterableWrapper) {
      return session.createPersistentEntityIterableWrapper(
              ((EntityIterable) left).getSource().union(((EntityIterable) right).getSource()));
    }
    return SequenceOperations.union(left, right);
  }

  public static Iterable<Entity> concat(@NotNull final TransientStoreSession session,
                                        @NotNull final Iterable<Entity> left,
                                        @NotNull final Iterable<Entity> right) {
    if (left instanceof PersistentEntityIterableWrapper && right instanceof PersistentEntityIterableWrapper) {
      return session.createPersistentEntityIterableWrapper(
              ((EntityIterable) left).getSource().concat(((EntityIterable) right).getSource()));
    }
    return SequenceOperations.concat(left, right);
  }

  public static Iterable<Entity> exclude(@NotNull final TransientStoreSession session,
                                         @NotNull final Iterable<Entity> left,
                                         @NotNull final Iterable<Entity> right) {
    if (left instanceof PersistentEntityIterableWrapper && right instanceof PersistentEntityIterableWrapper) {
      return session.createPersistentEntityIterableWrapper(
              ((EntityIterable) left).getSource().minus(((EntityIterable) right).getSource()));
    }
    return SequenceOperations.exclude(left, right);
  }

  public static boolean hasChanges(@NotNull TransientEntity e) {
    e = TransientStoreUtil.reattach(e);

    return e.hasChanges();
  }

  public static boolean hasChanges(@NotNull TransientEntity e, String property) {
    e = TransientStoreUtil.reattach(e);

    return e.hasChanges(property);
  }

  public static int indexOf(@NotNull Iterable<Entity> it, Entity e) {
    if (e == null) {
      return -1;
    }

    if (it instanceof PersistentEntityIterableWrapper) {
      return ((EntityIterable) it).getSource().indexOf(e);
    }

    return SequenceOperations.indexOf(it, e);
  }

  public static boolean contains(@NotNull Iterable<Entity> it, Entity e) {
    return indexOf(it, e) >= 0;
  }

  public static Entity getLast(@NotNull final TransientStoreSession session, @NotNull final Iterable<Entity> it) {
    if (it instanceof PersistentEntityIterableWrapper) {
      return session.getLast((EntityIterable) it);
    }
    return SequenceOperations.getLast(it);
  }

  public static boolean isCached(@NotNull final TransientStoreSession session,
                                 @NotNull final EntityIterable it,
                                 final boolean forceCachingIfNotCached) {
    final StoreSession persistentSession = session.getPersistentSession();
    final BerkeleyDbEntityStore store = (BerkeleyDbEntityStore) persistentSession.getStore();
    final BerkeleyDbEntityIterableCache cache = store.getEntityIterableCache();
    if (!forceCachingIfNotCached) {
      return cache.getCachedObject(it.getHandle()) != null;
    }
    final EntityIterable cached = putIfNotCached(cache, it);
    return cached != it;
  }

  public static EntityIterable putIfNotCached(@NotNull final BerkeleyDbEntityIterableCache cache,
                                              @NotNull final EntityIterable it) {
    try {
      return cache.putIfNotCached((EntityIterableBase) it);
    } catch (DatabaseException e) {
      throw new RuntimeException(e);
    }
  }
}
