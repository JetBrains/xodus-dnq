package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.exceptions.*;
import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.UndirectedAssociationSemantics;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import jetbrains.mps.baseLanguage.ext.collections.internal.query.SequenceOperations;
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
  static Set<DataIntegrityViolationException> checkAssociationsCardinality(@NotNull TransientChangesTracker changesTracker, @NotNull ModelMetaData modelMetaData) {
    Set<DataIntegrityViolationException> exceptions = new THashSet<DataIntegrityViolationException>();

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
            Set<String> changedLinks = changesTracker.getChangedLinks(e);
            if (changedLinks != null) {
              for (String changedLink : changedLinks) {
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

  static void processOnDeleteConstraints(@NotNull Entity e, @NotNull EntityMetaData emd) {
    Entity target;
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
        EntityOperations.remove(t, true);
      }
    }
  }

  static void processOnDeleteConstraintForDirectedAssociationEndToSingle(Entity e, AssociationEndMetaData amd) {
    Entity target;
    target = AssociationSemantics.getToOne(e, amd.getName());
    if (target != null) {
      DirectedAssociationSemantics.setToOne(e, amd.getName(), null);
      if (amd.getCascadeDelete()) {
        EntityOperations.remove(target, true);
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
            EntityOperations.remove(target, true);
          }
          break;

        case _0_n:
        case _1_n:
          // many to one
          UndirectedAssociationSemantics.removeOneToMany(target, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), amd.getName(), e);
          if (amd.getCascadeDelete()) {
            EntityOperations.remove(target, true);
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
            EntityOperations.remove(t, true);
          }
        }
        break;

      case _0_n:
      case _1_n:
        // many to many
        for (Entity t : AssociationSemantics.getToManyList(e, amd.getName())) {
          UndirectedAssociationSemantics.removeManyToMany(e, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), t);
          if (amd.getCascadeDelete()) {
            EntityOperations.remove(t, true);
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
        EntityOperations.remove(child, true);
      }
    }
  }

  static void processOnDeleteConstraintForParentEndToSingleChild(Entity e, AssociationEndMetaData amd) {
    Entity target;
    target = AssociationSemantics.getToOne(e, amd.getName());
    if (target != null) {
      AggregationAssociationSemantics.setOneToOne(e, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), null);
      if (amd.getCascadeDelete()) {
        EntityOperations.remove(target, true);
      }
    }
  }

  @NotNull
  static Set<DataIntegrityViolationException> checkUniqueProperties(@NotNull TransientStoreSession session, @NotNull TransientChangesTracker tracker, @NotNull ModelMetaData md) {
    Set<DataIntegrityViolationException> errors = new THashSet<DataIntegrityViolationException>();
    Map<String, Map<String, Set<Comparable>>> entityTypeToProperiesValues = new THashMap<String, Map<String, Set<Comparable>>>();

    for (TransientEntity e : tracker.getChangedEntities()) {
      if (!e.isRemoved()) {
        EntityMetaData emd = md.getEntityMetaData(e.getType());

        if (emd != null) {
          Set<String> uniqueProperties = emd.getUniqueProperties();
          Set<String> chanchedProperties = tracker.getChangedProperties(e);

          if (uniqueProperties.size() > 0 && (e.isNewOrTemporary() || (chanchedProperties != null && chanchedProperties.size() > 0))) {
            for (String uniquePropertyName : uniqueProperties) {
              if (e.isNewOrTemporary() || chanchedProperties.contains(uniquePropertyName)) {
                Comparable uniquePropertyValue = e.getProperty(uniquePropertyName);

                if (isEmptyProperty(uniquePropertyValue)) {
                  errors.add(new NullPropertyException(e, uniquePropertyName));
                } else {
                  Map<String, Set<Comparable>> propertiesValues = entityTypeToProperiesValues.get(e.getType());
                  if (propertiesValues == null) {
                    propertiesValues = new THashMap<String, Set<Comparable>>();
                    entityTypeToProperiesValues.put(e.getType(), propertiesValues);
                  }

                  Set<Comparable> propertyValues = propertiesValues.get(uniquePropertyName);
                  if (propertyValues == null) {
                    propertyValues = new THashSet<Comparable>();
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
    return SequenceOperations.getFirst(session.find(emd.getType(), propertyName, propertyValue));
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
    Set<DataIntegrityViolationException> errors = new THashSet<DataIntegrityViolationException>();

    for (TransientEntity e : tracker.getChangedEntities()) {
      if (!e.isRemoved()) {

        EntityMetaData emd = md.getEntityMetaData(e.getType());

        if (emd != null) {
          Set<String> requiredProperties = emd.getRequiredProperties();
          Set<String> requiredIfProperties = emd.getRequiredIfProperties(e);
          Set<String> changhedProperties = tracker.getChangedProperties(e);

          if ((requiredProperties.size()+requiredIfProperties.size() > 0 && (e.isNewOrTemporary() || (changhedProperties != null && changhedProperties.size() > 0)))) {
             for (String requiredPropertyName : requiredProperties) {
                 checkProperty(errors, e, changhedProperties, requiredPropertyName);
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

    private static void checkProperty(Set<DataIntegrityViolationException> errors, TransientEntity e, Set<String> changhedProperties, String propertyName) {
        if (e.isNewOrTemporary() || changhedProperties.contains(propertyName)) {
            checkProperty(errors, e, propertyName);
        }
    }

}
