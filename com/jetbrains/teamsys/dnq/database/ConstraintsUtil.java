package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.decorators.HashSetDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.HashMapDecorator;
import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.core.dataStructures.hash.HashSet;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.impl.iterate.EntityIteratorBase;
import com.jetbrains.teamsys.database.exceptions.*;
import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.UndirectedAssociationSemantics;
import jetbrains.mps.internal.collections.runtime.ListSequence;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

class ConstraintsUtil {

  private static final Log log = LogFactory.getLog(ConstraintsUtil.class);

  //TODO: performance tip: use getPersistentIterable.next instead of getLinksSize
  static boolean checkCardinality(TransientEntity e, AssociationEndMetaData md) {
    long size;

    switch (md.getCardinality()) {
      case _0_1:
        size = e.getLinksSize(md.getName());
        return size <= 1;

      case _0_n:
        return true;

      case _1:
        size = e.getLinksSize(md.getName());
        return size == 1;

      case _1_n:
        size = e.getLinksSize(md.getName());
        return size >= 1;
    }

    throw new IllegalArgumentException("Unknown cardinality [" + md.getCardinality() + "]");
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
              boolean bad = false;
              for (String key : incomingLinks.keySet()) {
                  TransientEntity entity = (TransientEntity) e.getStore().getThreadSession().getEntity(incomingLinks.get(key));
                  String type = entity.getType();

                  EntityMetaData metaData = modelMetaData.getEntityMetaData(type);
                  AssociationEndMetaData end = metaData.getAssociationEndMetaData(key);

                  if (end.getTargetClearOnDelete() || entity.isRemoved()) {
                      continue;
                  }

                  bad = true;
                  _incomingLinks.put(key, entity);
              }
              if (bad) exceptions.add(new ConstraintsValidationException(new CantRemoveEntityException(e, _incomingLinks)));
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

    return exceptions;
  }

  static void processOnDeleteConstraints(@NotNull StoreSession session, @NotNull Entity e, @NotNull EntityMetaData emd, @NotNull ModelMetaData md) {
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

    private static void processOnTargetDeleteConstraints(Entity source, EntityMetaData emd, ModelMetaData md, String oppositeType, String linkName, StoreSession session) {
        EntityMetaData oppositeEmd = md.getEntityMetaData(oppositeType);
        if (oppositeEmd == null) {
            throw new RuntimeException("can't find metadata for entity type " + oppositeType + " as opposite to " + source.getType());
        }
        AssociationEndMetaData amd = oppositeEmd.getAssociationEndMetaData(linkName);
        final EntityIterator it = session.findLinks(oppositeType, source, linkName).iterator();
        while (it.hasNext()) {
          Entity e = it.next();
          // System.out.println("opposite entity (instance of " + oppositeType + "): " + e + ", link name: " + linkName);
          if (amd.getTargetCascadeDelete()) {
            EntityOperations.remove(e);
          } else if (amd.getTargetClearOnDelete()) {
            if (log.isDebugEnabled()) {
              if (amd.getTargetCascadeDelete()) {
                log.debug("Cascade delete targets for link [" + e + "]." + amd.getName());
              }
    
              if (amd.getTargetClearOnDelete()) {
                log.debug("Clear associations with targets for link [" + e + "]." + amd.getName());
              }
            }
            // TODO: (optimization hint) write new switch, which methods doesn't call "if (amd.getCascadeDelete()) EntityOperations.remove(t)" and source is provided by our local variable
            processOnDeleteConstrainsSwitch(e, amd);
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

  @NotNull
  static Set<DataIntegrityViolationException> checkUniqueProperties(@NotNull TransientStoreSession session, @NotNull TransientChangesTracker tracker, @NotNull ModelMetaData md) {
    Set<DataIntegrityViolationException> errors = new HashSetDecorator<DataIntegrityViolationException>();
    Map<String, Map<String, Set<Comparable>>> entityTypeToProperiesValues = new HashMap<String, Map<String, Set<Comparable>>>();

    for (TransientEntity e : tracker.getChangedEntities()) {
      if (!e.isRemoved()) {
        final String entityType = e.getType();
        EntityMetaData emd = md.getEntityMetaData(entityType);

        if (emd != null) {
          Set<String> uniqueProperties = emd.getUniqueProperties();
          Map<String, PropertyChange> changedProperties = tracker.getChangedPropertiesDetailed(e);

          if (uniqueProperties.size() > 0 && (e.isNewOrTemporary() || (changedProperties != null && changedProperties.size() > 0))) {
            for (String uniquePropertyName : uniqueProperties) {
              if (e.isNewOrTemporary() || changedProperties.containsKey(uniquePropertyName)) {
                Comparable uniquePropertyValue = e.getProperty(uniquePropertyName);

                if (isEmptyProperty(uniquePropertyValue)) {
                  errors.add(new NullPropertyException(e, uniquePropertyName));
                } else {
                  Map<String, Set<Comparable>> propertiesValues = entityTypeToProperiesValues.get(entityType);
                  if (propertiesValues == null) {
                    propertiesValues = new HashMap<String, Set<Comparable>>();
                    entityTypeToProperiesValues.put(entityType, propertiesValues);
                  }

                  Set<Comparable> propertyValues = propertiesValues.get(uniquePropertyName);
                  if (propertyValues == null) {
                    propertyValues = new HashSet<Comparable>();
                    propertiesValues.put(uniquePropertyName, propertyValues);
                  }

                  if (!propertyValues.add(uniquePropertyValue)) {
                    errors.add(new UniqueConstraintViolationException(e, uniquePropertyName));
                  } else {
                    // find in database and if found - be sure we found another entity
                    Entity _e = findInDatabase(session, emd, uniquePropertyName, uniquePropertyValue);
                    if (!(_e == null || _e.getId().equals(e.getId()))) {
                      //TODO: optimization hint: query database only once for every property type (property type = entity type + property name)
                      errors.add(new UniqueConstraintViolationException(e, uniquePropertyName));
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    return errors;
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

    private static void checkProperty(Set<DataIntegrityViolationException> errors, TransientEntity e, String propertyName) {
        Comparable propertyValue = e.getProperty(propertyName);
        if (isEmptyProperty(propertyValue)) {
            errors.add(new NullPropertyException(e, propertyName));
        }
    }

    private static void checkProperty(Set<DataIntegrityViolationException> errors, TransientEntity e, Map<String, PropertyChange> changhedProperties, String propertyName) {
        if (e.isNewOrTemporary() || changhedProperties.containsKey(propertyName)) {
            checkProperty(errors, e, propertyName);
        }
    }

}
