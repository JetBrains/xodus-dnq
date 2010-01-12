package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.decorators.HashSetDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.HashMapDecorator;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.exceptions.*;
import com.jetbrains.teamsys.dnq.association.*;
import jetbrains.mps.internal.collections.runtime.ListSequence;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.List;

class ConstraintsUtil {

  private static final Log log = LogFactory.getLog(ConstraintsUtil.class);

  //TODO: performance tip: use getPersistentIterable.next instead of getLinksSize
  static boolean checkCardinality(TransientEntity e, AssociationEndMetaData md) {
    return checkCardinality(e, md.getCardinality(), md.getName());
  }

  static boolean checkCardinality(TransientEntity e, AssociationEndCardinality cardinality, String associationName) {
    long size;

    switch (cardinality) {
      case _0_1:
        size = e.getLinksSize(associationName);
        return size <= 1;

      case _0_n:
        return true;

      case _1:
        size = e.getLinksSize(associationName);
        return size == 1;

      case _1_n:
        size = e.getLinksSize(associationName);
        return size >= 1;
    }

    throw new IllegalArgumentException("Unknown cardinality [" + cardinality + "]");
  }

  @NotNull
  static Set<DataIntegrityViolationException> executeBeforeFlushTriggers(@NotNull TransientChangesTracker changesTracker, @NotNull ModelMetaData modelMetaData) {
      Set<DataIntegrityViolationException> exceptions = new HashSetDecorator<DataIntegrityViolationException>();

      Set<TransientEntity> changedEntities = changesTracker.getChangedEntities();
      for (TransientEntity e : changedEntities.toArray(new TransientEntity[changedEntities.size()])) {
      if (!e.isRemoved()) {
        EntityMetaData md = modelMetaData.getEntityMetaData(e.getType());

        // meta-data may be null for persistent enums
        if (md != null) {
          try {
            md.getInstance(e).executeBeforeFlushTrigger(e);
          } catch (ConstraintsValidationException cve) {
            for (DataIntegrityViolationException dive: cve.getCauses()) {
              exceptions.add(dive);
            }
          }
        }
      }
    }

    return exceptions;
  }

  @NotNull
  static Set<DataIntegrityViolationException> checkIncomingLinks(@NotNull TransientChangesTracker changesTracker, @NotNull ModelMetaData modelMetaData) {
      Set<DataIntegrityViolationException> exceptions = new HashSetDecorator<DataIntegrityViolationException>();

      for (TransientEntity e : changesTracker.getChangedEntities()) {
        if (e.isRemoved()) {
            Map<String, EntityId> incomingLinks = e.getIncomingLinks();

            if (incomingLinks.size() > 0) {
              String sourceType = e.getType();

              Map<String, TransientEntity> _incomingLinks = new HashMapDecorator<String, TransientEntity>();
              for (String key : incomingLinks.keySet()) {
                  TransientEntity entity = (TransientEntity) e.getStore().getThreadSession().getEntity(incomingLinks.get(key));

                  if (entity.isRemoved() || entity.getRemovedLinks(key).contains(e)) {
                    continue;
                  }

                  String type = entity.getType();

                  EntityMetaData metaData = modelMetaData.getEntityMetaData(type);
                  AssociationEndMetaData end = metaData.getAssociationEndMetaData(key);

                  if (end.getTargetClearOnDelete() || end.getTargetCascadeDelete()) {
                    continue;
                  }

                  AssociationMetaData associationMetaData = end.getAssociationMetaData();
                  if (!associationMetaData.getType().equals(AssociationType.Directed)) {
                      AssociationEndMetaData oppositeEnd = associationMetaData.getOppositeEnd(end);
                      if (oppositeEnd.getClearOnDelete() || oppositeEnd.getCascadeDelete()) {
                      continue;
                    }
                  }

                  _incomingLinks.put(key, entity);
              }
              if (_incomingLinks.size() > 0) exceptions.add(new CantRemoveEntityException(e, _incomingLinks));
            }
        }
      }

      return exceptions;
  }

  @NotNull
  static Set<DataIntegrityViolationException> checkAssociationsCardinality(@NotNull TransientChangesTracker changesTracker, @NotNull ModelMetaData modelMetaData) {
    Set<DataIntegrityViolationException> exceptions = new HashSetDecorator<DataIntegrityViolationException>();

    for (TransientEntity e : changesTracker.getChangedEntities()) {
      if (!e.isRemoved()) {
        // if entity is new - check cardinality of all links
        // if entity saved - check cardinality of changed links only
        EntityMetaData md = modelMetaData.getEntityMetaData(e.getType());

        // meta-data may be null for persistent enums
        if (md != null) {
          if (e.isNewOrTemporary()) {
            // check all links of new entity
            for (AssociationEndMetaData aemd : md.getAssociationEndsMetaData()) {
              if (log.isTraceEnabled()) {
                log.trace("Check cardinality [" + e.getType() + "." + aemd.getName() + "]. Required is [" + aemd.getCardinality().getName() + "]");
              }

              if (!checkCardinality(e, aemd)) {
                exceptions.add(new CardinalityViolationException(e, aemd));
              }
            }
          } else if (e.isSaved()) {
            // check only changed links of saved entity
            Map<String, LinkChange> changedLinks = changesTracker.getChangedLinksDetailed(e);
            if (changedLinks != null) {
              for (String changedLink : changedLinks.keySet()) {
                AssociationEndMetaData aemd = md.getAssociationEndMetaData(changedLink);

                if (aemd == null) {
                  log.error("aemd is null. Type: [" + e.getType() + "]. Changed link: " + changedLink);
                } else {
                  if (log.isTraceEnabled()) {
                    log.trace("Check cardinality [" + e.getType() + "." + aemd.getName() + "]. Required is [" + aemd.getCardinality().getName() + "]");
                  }

                  if (!checkCardinality(e, aemd)) {
                    exceptions.add(new CardinalityViolationException(e, aemd));
                  }

                }

              }
            }
          }
        }
      }
    }

    return exceptions;
  }

  static void processOnDeleteConstraints(@NotNull TransientStoreSession session, @NotNull Entity e, @NotNull EntityMetaData emd, @NotNull ModelMetaData md) {
    for (AssociationEndMetaData amd : emd.getAssociationEndsMetaData()) {
      if (amd.getCascadeDelete() || amd.getClearOnDelete()) {

        if (log.isDebugEnabled()) {
          if (amd.getCascadeDelete()) {
            log.debug("Cascade delete targets for link [" + e + "]." + amd.getName());
          }

          if (amd.getClearOnDelete()) {
            log.debug("Clear associations with targets for link [" + e + "]." + amd.getName());
          }
        }
        processOnDeleteConstrainsSwitch(e, amd);
      }
    }

   Map<String, Set<String>> incomingAssociations = emd.getIncomingAssociations(md);
   for (String key: incomingAssociations.keySet()) {
       for (String linkName: incomingAssociations.get(key)) {
           processOnTargetDeleteConstraints(e, emd, md, key, linkName, session);
       }
   }
  }

    private static void processOnDeleteConstrainsSwitch(Entity e, AssociationEndMetaData amd) {
        // cascade delete depend association end type and cardinality
        switch (amd.getAssociationEndType()) {
          case ParentEnd:
            switch (amd.getCardinality()) {

              case _0_1:
              case _1:
                processOnDeleteConstraintForParentEndToSingleChild(e, amd);
                break;

              case _0_n:
              case _1_n:
                processOnDeleteConstraintForParentEndToMultipleChild(e, amd);
                break;
            }
            break;

          case ChildEnd:
            switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
              case _0_1:
              case _1:
                processOnDeleteConstraintsForSingleChildEnd(e, amd);
                break;

              case _0_n:
              case _1_n:
                processOnDeleteConstraintsForMultipleChildEnd(e, amd);
                break;
            }
            break;

          case UndirectedAssociationEnd:
            switch (amd.getCardinality()) {
              case _0_1:
              case _1:
                processOnDeleteConstraintForUndirectedAssociationEndToSingle(e, amd);
                break;

              case _0_n:
              case _1_n:
                processOnDeleteConstraintForUndirectedAssociationEndToMultiple(e, amd);
                break;
            }
            break;

          case DirectedAssociationEnd:
            switch (amd.getCardinality()) {
              case _0_1:
              case _1:
                processOnDeleteConstraintForDirectedAssociationEndToSingle(e, amd);
                break;

              case _0_n:
              case _1_n:
                processOnDeleteConstraintForDirectedAssociationEndToMultiple(e, amd);
                break;
            }
            break;

          default:
            throw new IllegalArgumentException("Cascade delete is not supported for association end type [" + amd.getAssociationEndType() + "]");
        }
    }

    private static void processOnTargetDeleteConstrainsSwitch(Entity source, Entity target, String linkName, AssociationEndMetaData amd) {
        // cascade delete depend association end type and cardinality
        switch (amd.getAssociationEndType()) {
          case ParentEnd:
            switch (amd.getCardinality()) {

              case _0_1:
              case _1:
                processOnTargetDeleteConstraintForParentEndToSingleChild(source, linkName, amd);
                break;

              case _0_n:
              case _1_n:
                processOnTargetDeleteConstraintForParentEndToMultipleChild(source, target, linkName, amd);
                break;
            }
            break;

          case ChildEnd:
            switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
              case _0_1:
              case _1:
                processOnTargetDeleteConstraintsForSingleChildEnd(source, linkName, amd);
                break;

              case _0_n:
              case _1_n:
                processOnTargetDeleteConstraintsForMultipleChildEnd(source, target, linkName, amd);
                break;
            }
            break;

          case UndirectedAssociationEnd:
            switch (amd.getCardinality()) {
              case _0_1:
              case _1:
                processOnTargetDeleteConstraintForUndirectedAssociationEndToSingle(source, target, linkName, amd);
                break;

              case _0_n:
              case _1_n:
                processOnTargetDeleteConstraintForUndirectedAssociationEndToMultiple(source, target, linkName, amd);
                break;
            }
            break;

          case DirectedAssociationEnd:
            switch (amd.getCardinality()) {
              case _0_1:
              case _1:
                processOnTargetDeleteConstraintForDirectedAssociationEndToSingle(source, linkName);
                break;

              case _0_n:
              case _1_n:
                processOnTargetDeleteConstraintForDirectedAssociationEndToMultiple(source, target, linkName);
                break;
            }
            break;

          default:
            throw new IllegalArgumentException("Cascade delete is not supported for association end type [" + amd.getAssociationEndType() + "]");
        }
    }

    private static void processOnTargetDeleteConstraints(Entity target, EntityMetaData emd, ModelMetaData md, String oppositeType, String linkName, TransientStoreSession session) {
        EntityMetaData oppositeEmd = md.getEntityMetaData(oppositeType);
        if (oppositeEmd == null) {
            throw new RuntimeException("can't find metadata for entity type " + oppositeType + " as opposite to " + target.getType());
        }
        AssociationEndMetaData amd = oppositeEmd.getAssociationEndMetaData(linkName);
        final EntityIterator it = session.findLinks(oppositeType, target, linkName).iterator();
        TransientChangesTracker changesTracker = session.getTransientChangesTracker();
        while (it.hasNext()) {
          Entity source = it.next();
          Map<String, LinkChange> changedLinks = changesTracker.getChangedLinksDetailed((TransientEntity) source);
          boolean linkAlreadyRemoved = false;
          if (changedLinks != null) { // changed links can be null
            LinkChange change = changedLinks.get(linkName);
            if (change != null) { // change can be null if current link is not changed, but some was
              LinkChangeType changeType = change.getChangeType();
              if ((changeType == LinkChangeType.REMOVE || changeType == LinkChangeType.SET) && change.getRemovedEntities().contains(target)) {
                linkAlreadyRemoved = true;
              } else if ((changeType == LinkChangeType.ADD || changeType == LinkChangeType.SET) && change.getAddedEntities().contains(target)) {
                continue;
              }
            }
          }
          // System.out.println("opposite entity (instance of " + oppositeType + "): " + source + ", link name: " + linkName);
          if (amd.getTargetCascadeDelete()) {
            if (log.isDebugEnabled()) {
              log.debug("cascade delete targets for link [" + source + "]." + linkName);
            }
            EntityOperations.remove(source);
          } else if ((!linkAlreadyRemoved) && amd.getTargetClearOnDelete()) {
            if (log.isDebugEnabled()) {
              log.debug("clear associations with targets for link [" + source + "]." + linkName);
            }
            processOnTargetDeleteConstrainsSwitch(source, target, linkName, amd);
          }
        }
    }


  private static void processOnDeleteConstraintsForSingleChildEnd(Entity child, AssociationEndMetaData amd) {
    Entity parent = AssociationSemantics.getToOne(child, amd.getName());

    if (parent != null) {
      AggregationAssociationSemantics.setOneToOne(parent, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), amd.getName(), null);
    }
  }

  private static void processOnDeleteConstraintsForMultipleChildEnd(Entity child, AssociationEndMetaData amd) {
    Entity parent = AssociationSemantics.getToOne(child, amd.getName());

    if (parent != null) {
      AggregationAssociationSemantics.removeOneToMany(parent, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), amd.getName(), child);
    }
  }

  static void processOnDeleteConstraintForDirectedAssociationEndToMultiple(Entity e, AssociationEndMetaData amd) {
    for (Entity t : AssociationSemantics.getToManyList(e, amd.getName())) {
      DirectedAssociationSemantics.removeToMany(e, amd.getName(), t);
      if (amd.getCascadeDelete()) {
        EntityOperations.remove(t);
      }
    }
  }

  static void processOnDeleteConstraintForDirectedAssociationEndToSingle(Entity e, AssociationEndMetaData amd) {
    Entity target;
    target = AssociationSemantics.getToOne(e, amd.getName());
    if (target != null) {
      DirectedAssociationSemantics.setToOne(e, amd.getName(), null);
      if (amd.getCascadeDelete()) {
        EntityOperations.remove(target);
      }
    }
  }

  static void processOnDeleteConstraintForUndirectedAssociationEndToSingle(Entity e, AssociationEndMetaData amd) {
    Entity target;
    target = AssociationSemantics.getToOne(e, amd.getName());
    if (target != null) {
      switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
        case _0_1:
        case _1:
          // one to one
          UndirectedAssociationSemantics.setOneToOne(e, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), null);
          if (amd.getCascadeDelete()) {
            EntityOperations.remove(target);
          }
          break;

        case _0_n:
        case _1_n:
          // many to one
          UndirectedAssociationSemantics.removeOneToMany(target, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), amd.getName(), e);
          if (amd.getCascadeDelete()) {
            EntityOperations.remove(target);
          }
          break;
      }

    }
  }

  static void processOnDeleteConstraintForUndirectedAssociationEndToMultiple(Entity e, AssociationEndMetaData amd) {
    switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
      case _0_1:
      case _1:
        // one to many
        for (Entity t : AssociationSemantics.getToManyList(e, amd.getName())) {
          UndirectedAssociationSemantics.removeOneToMany(e, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), t);
          if (amd.getCascadeDelete()) {
            EntityOperations.remove(t);
          }
        }
        break;

      case _0_n:
      case _1_n:
        // many to many
        for (Entity t : AssociationSemantics.getToManyList(e, amd.getName())) {
          UndirectedAssociationSemantics.removeManyToMany(e, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), t);
          if (amd.getCascadeDelete()) {
            EntityOperations.remove(t);
          }
        }
        break;
    }

  }

  static void processOnDeleteConstraintForParentEndToMultipleChild(Entity e, AssociationEndMetaData amd) {
    for (Entity child : AssociationSemantics.getToManyList(e, amd.getName())) {
      try {
        AggregationAssociationSemantics.removeOneToMany(
              e, amd.getName(),
              amd.getAssociationMetaData().getOppositeEnd(amd).getName(), child);
      } catch (EntityRemovedException ex) {
        if (log.isDebugEnabled()) {
          log.debug("Entity [" + child + "] already removed", ex);          
        }
      }
      if (amd.getCascadeDelete()) {
        EntityOperations.remove(child);
      }
    }
  }

  static void processOnDeleteConstraintForParentEndToSingleChild(Entity e, AssociationEndMetaData amd) {
    Entity target;
    target = AssociationSemantics.getToOne(e, amd.getName());
    if (target != null) {
      AggregationAssociationSemantics.setOneToOne(e, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), null);
      if (amd.getCascadeDelete()) {
        EntityOperations.remove(target);
      }
    }
  }

  private static void processOnTargetDeleteConstraintsForSingleChildEnd(@NotNull Entity parent, @NotNull String linkName, AssociationEndMetaData amd) {
    AggregationAssociationSemantics.setOneToOne(parent, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), linkName, null);
  }

  private static void processOnTargetDeleteConstraintsForMultipleChildEnd(@NotNull Entity parent, @NotNull Entity child, @NotNull String linkName, AssociationEndMetaData amd) {
    AggregationAssociationSemantics.removeOneToMany(parent, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), linkName, child);
  }

  private static void processOnTargetDeleteConstraintForDirectedAssociationEndToMultiple(@NotNull Entity source, @NotNull Entity target, @NotNull String linkName) {
    DirectedAssociationSemantics.removeToMany(source, linkName, target);
  }

  private static void processOnTargetDeleteConstraintForDirectedAssociationEndToSingle(@NotNull Entity source, @NotNull String linkName) {
    DirectedAssociationSemantics.setToOne(source, linkName, null);
  }

  private static void processOnTargetDeleteConstraintForUndirectedAssociationEndToSingle(@NotNull Entity source, @NotNull Entity target, @NotNull String linkName, AssociationEndMetaData amd) {
    switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
      case _0_1:
      case _1:
        // one to one
        UndirectedAssociationSemantics.setOneToOne(source, linkName, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), null);
        break;

      case _0_n:
      case _1_n:
        // many to one
        UndirectedAssociationSemantics.removeOneToMany(source, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), linkName, target);
        break;
     }
  }

  private static void processOnTargetDeleteConstraintForUndirectedAssociationEndToMultiple(@NotNull Entity source, @NotNull Entity target, @NotNull String linkName, AssociationEndMetaData amd) {
    switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
      case _0_1:
      case _1:
        // one to many
        UndirectedAssociationSemantics.removeOneToMany(source, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), target);
        break;

      case _0_n:
      case _1_n:
        // many to many
        UndirectedAssociationSemantics.removeManyToMany(source, linkName, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), target);
        break;
    }

  }

  private static void processOnTargetDeleteConstraintForParentEndToMultipleChild(@NotNull Entity parent, @NotNull Entity child, @NotNull String linkName, AssociationEndMetaData amd) {
    try {
      AggregationAssociationSemantics.removeOneToMany(parent, linkName, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), child);
    } catch (EntityRemovedException ex) {
      if (log.isDebugEnabled()) {
        log.debug("Entity [" + child + "] already removed", ex);
      }
    }
  }

  private static void processOnTargetDeleteConstraintForParentEndToSingleChild(@NotNull Entity source, @NotNull String linkName, AssociationEndMetaData amd) {
    AggregationAssociationSemantics.setOneToOne(source, linkName, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), null);
  }

  private static Entity findInDatabase(TransientStoreSession session, EntityMetaData emd, String propertyName, Comparable propertyValue) {
    return ListSequence.fromIterable(session.find(emd.getType(), propertyName, propertyValue)).first();
  }

  private static boolean isEmptyProperty(Comparable propertyValue) {
    if (propertyValue == null) {
      return true;
    }

    if (propertyValue instanceof String && "".equals(propertyValue)) {
      return true;
    }

    return false;
  }

  @NotNull
  static Set<DataIntegrityViolationException> checkRequiredProperties(@NotNull TransientChangesTracker tracker, @NotNull ModelMetaData md) {
    Set<DataIntegrityViolationException> errors = new HashSetDecorator<DataIntegrityViolationException>();

    for (TransientEntity e : tracker.getChangedEntities()) {
      if (!e.isRemoved()) {

        EntityMetaData emd = md.getEntityMetaData(e.getType());

        if (emd != null) {
          Set<String> requiredProperties = emd.getRequiredProperties();
          Set<String> requiredIfProperties = emd.getRequiredIfProperties(e);
          Map<String, PropertyChange> changedProperties = tracker.getChangedPropertiesDetailed(e);

          if ((requiredProperties.size()+requiredIfProperties.size() > 0 && (e.isNewOrTemporary() || (changedProperties != null && changedProperties.size() > 0)))) {
             for (String requiredPropertyName : requiredProperties) {
                 checkProperty(errors, e, changedProperties, requiredPropertyName);
             }
             for (String requiredIfPropertyName : requiredIfProperties) {
                 checkProperty(errors, e, requiredIfPropertyName);
             }
          }
        }
      }
    }

    return errors;
  }

  /**
   * Properties and associations, that are part of indexes, can't be empty
   * @param tracker
   * @param md
   * @return
   */
  @NotNull
  static Set<DataIntegrityViolationException> checkIndexFields(@NotNull TransientChangesTracker tracker, @NotNull ModelMetaData md) {
    Set<DataIntegrityViolationException> errors = new HashSetDecorator<DataIntegrityViolationException>();

    for (TransientEntity e : tracker.getChangedEntities()) {
      if (!e.isRemoved()) {

        EntityMetaData emd = md.getEntityMetaData(e.getType());

        if (emd != null) {
          Map<String, PropertyChange> changedProperties = tracker.getChangedPropertiesDetailed(e);
          Set<Index> indexes = emd.getIndexes();

          for (Index index : indexes) {
            for (IndexField f : index.getFields()) {
              if (f.isProperty()) {
                if (e.isNewOrTemporary() || (changedProperties != null && changedProperties.size() > 0)) {
                  checkProperty(errors, e, changedProperties, f.getName());
                }
              } else {
                // link
                if (!checkCardinality(e, AssociationEndCardinality._1, f.getName())) {
                  errors.add(new CardinalityViolationException("Association [" + f.getName() +  "] can't be empty, because it's part of unique constraint.", e, f.getName()));
                }
              }
            }
          }
        }
      }
    }

    return errors;
  }

  private static void checkProperty(Set<DataIntegrityViolationException> errors, TransientEntity e, String propertyName) {
      Comparable propertyValue = e.getProperty(propertyName);
      if (isEmptyProperty(propertyValue)) {
          errors.add(new NullPropertyException(e, propertyName));
      }
  }

  private static void checkProperty(Set<DataIntegrityViolationException> errors, TransientEntity e, Map<String, PropertyChange> changedProperties, String propertyName) {
      if (e.isNewOrTemporary() || changedProperties.containsKey(propertyName)) {
          checkProperty(errors, e, propertyName);
      }
  }

}
