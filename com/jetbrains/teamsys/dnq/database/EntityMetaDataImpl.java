package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.decorators.HashMapDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.HashSetDecorator;
import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.core.dataStructures.hash.HashSet;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import jetbrains.teamsys.dnq.runtime.util.DnqUtils;

public class EntityMetaDataImpl implements EntityMetaData {

  private String type = null;
  private String superType = null;
  private Runnable initializer = null;
  private boolean removeOrphan = true;
  private Set<String> subTypes = new HashSetDecorator<String>();
  private List<String> thisAndSuperTypes = Collections.emptyList();
  private Map<String, AssociationEndMetaData> associationEnds = null;
  private Set<AssociationEndMetaData> externalAssociationEnds = null;
  private Set<String> aggregationChildEnds = null;
  private Set<Index> ownIndexes = Collections.emptySet();
  private Set<Index> indexes = null;
  private Map<String, Set<Index>> fieldToIndexes = null;
  private Set<String> requiredProperties = Collections.emptySet();
  private Set<String> requiredIfProperties = Collections.emptySet();
  private Set<String> historyIgnoredFields = Collections.emptySet();
  private Set<String> versionMismatchIgnored = Collections.emptySet();
  private Map<String, Set<String>> incomingAssociations = null;
  private boolean versionMismatchIgnoredForWholeClass = false;

  public void setType(String type) {
    this.type = type;
  }

  public void setSuperType(String superType) {
    this.superType = superType;
  }

  public Iterable<String> getThisAndSuperTypes() {
    return thisAndSuperTypes;
  }

  public void setThisAndSuperTypes(List<String> thisAndSuperTypes) {
    this.thisAndSuperTypes = thisAndSuperTypes;
  }

  public boolean hasSubTypes() {
    return !subTypes.isEmpty();
  }

  public Iterable<String> getSubTypes() {
    return subTypes;
  }

  public Iterable<String> getAllSubTypes(ModelMetaData mmd) {
    if (!hasSubTypes()) return Collections.emptyList();
    List<String> result = new ArrayList<String>(subTypes.size());
    collectSubTypes(this, mmd, result);
    return result;
  }

  private static void collectSubTypes(EntityMetaDataImpl emd, ModelMetaData mmd, List<String> result) {
    final Set<String> subTypes = emd.subTypes;
    result.addAll(subTypes);
    for (final String subType : subTypes) {
      collectSubTypes((EntityMetaDataImpl) mmd.getEntityMetaData(subType), mmd, result);
    }
  }

  public void addSubType(@NotNull String type) {
    subTypes.add(type);
  }

  public void setInstanceRef(InstanceRef instanceRef) {
    // TODO: remove this method when no textual usages of '<property name="instanceRef">' in entityMetaDataConfiguration.xml left
  }

  public void setInitializer(Runnable initializer) {
    this.initializer = initializer;
  }

  public Runnable getInitializer() {
    return initializer;
  }

  public void setHistoryIgnoredFields(Set<String> historyIgnoredFields) {
    this.historyIgnoredFields = historyIgnoredFields;
  }

  public boolean changesReflectHistory(TransientEntity e, TransientChangesTracker tracker) {
    Map<String, PropertyChange> changedProperties = tracker.getChangedPropertiesDetailed(e);
    if (changedProperties != null) {
      for (String field : changedProperties.keySet()) {
        if (!historyIgnoredFields.contains(field)) {
          return true;
        }
      }
    }

    Map<String, LinkChange> changedLinks = tracker.getChangedLinksDetailed(e);
    if (changedLinks != null) {
      for (String field : changedLinks.keySet()) {
        if (!historyIgnoredFields.contains(field)) {
          return true;
        }
      }
    }

    return false;
  }

  public void setRemoveOrphan(boolean removeOrphan) {
    this.removeOrphan = removeOrphan;
  }

  public void setAssociationEnds(Set<AssociationEndMetaData> associationEnds) {
    externalAssociationEnds = associationEnds;
  }

  @NotNull
  public String getType() {
    return type;
  }

  @Nullable
  public String getSuperType() {
    return superType;
  }

  public AssociationEndMetaData getAssociationEndMetaData(@NotNull String name) {
    checkAssociationEndsCreated();
    return associationEnds.get(name);
  }

  @NotNull
  public Iterable<AssociationEndMetaData> getAssociationEndsMetaData() {
    checkAssociationEndsCreated();
    return associationEnds.values();
  }

  public boolean getRemoveOrphan() {
    return removeOrphan;
  }

  public boolean hasAggregationChildEnds() {
    checkAssociationEndsCreated();
    return !aggregationChildEnds.isEmpty();
  }

  public Set<String> getAggregationChildEnds() {
    checkAssociationEndsCreated();
    return aggregationChildEnds;
  }

  @NotNull
  public Map<String, Set<String>> getIncomingAssociations(final ModelMetaData mmd) {
    if (incomingAssociations == null) {
      synchronized (this) {
        if (incomingAssociations == null) {
          incomingAssociations = new HashMapDecorator<String, Set<String>>();
          final Set<String> typeWithSubTypes = new HashSet<String>();
          typeWithSubTypes.add(type);
          for (final String subType : getSubTypes()) {
            typeWithSubTypes.add(subType);
          }
          for (final EntityMetaData emd : mmd.getEntitiesMetaData()) {
            for (final AssociationEndMetaData aemd : emd.getAssociationEndsMetaData()) {
              if (typeWithSubTypes.contains(aemd.getOppositeEntityMetaData().getType())) {
                final String associationName = aemd.getName();
                addIncomingAssociation(emd.getType(), associationName);
                for (final String subtype : emd.getSubTypes()) {
                  addIncomingAssociation(subtype, associationName);
                }
              }
            }
          }
        }
      }
    }
    return incomingAssociations;
  }

  private void addIncomingAssociation(@NotNull final String type, @NotNull final String associationName) {
    Set<String> links = incomingAssociations.get(type);
    if (links == null) {
      links = new HashSet<String>();
      incomingAssociations.put(type, links);
    }
    links.add(associationName);
  }

  @NotNull
  public Set<Index> getOwnIndexes() {
    return ownIndexes;  
  }

  @NotNull
  public Set<Index> getIndexes() {
    if (indexes == null) {
      indexes = new HashSet<Index>();

      // add indexes of super types
      for (String t : getThisAndSuperTypes()) {
        for (Index index : getEntityMetaData(t).getOwnIndexes()) {
          for (IndexField f : index.getFields()) {
            for (String st : getEntityMetaData(f.getOwnerEnityType()).getThisAndSuperTypes()) {
              indexes.addAll(getEntityMetaData(st).getOwnIndexes());
            }
          }
        }
      }

    }
    return indexes;
  }

  private EntityMetaData getEntityMetaData(String type) {
    return DnqUtils.getModelMetaData().getEntityMetaData(type);
  }

  @NotNull
  public void setOwnIndexes(Set<Index> ownIndexes) {
    this.ownIndexes = ownIndexes;
  }

  @NotNull
  public Set<Index> getIndexes(String field) {
    if (fieldToIndexes == null) {
      fieldToIndexes = new HashMap<String, Set<Index>>();
      // build prop to ownIndexes map
      for (Index index : getIndexes()) {
        for (IndexField f : index.getFields()) {
          Set<Index> fieldIndexes = fieldToIndexes.get(f.getName());
          if (fieldIndexes == null) {
            fieldIndexes = new HashSet<Index>();
            fieldToIndexes.put(f.getName(), fieldIndexes);
          }
          fieldIndexes.add(index);
        }
      }
    }
    Set<Index> res = fieldToIndexes.get(field);
    return res == null ? Collections.<Index>emptySet() : res;
  }

  @NotNull
  public Set<String> getRequiredProperties() {
    return requiredProperties;
  }

  @NotNull
  public Set<String> getRequiredIfProperties(Entity e) {
    Set<String> result = new HashSetDecorator<String>();
    for (String property : requiredIfProperties) {
      if (getInstance(e).isPropertyRequired(property, e)) {
        result.add(property);
      }
    }
    return result;
  }

  public boolean isVersionMismatchIgnoredForWholeClass() {
    return versionMismatchIgnoredForWholeClass;
  }

  public void setVersionMismatchIgnoredForWholeClass(boolean versionMismatchIgnoredForWholeClass) {
    this.versionMismatchIgnoredForWholeClass = versionMismatchIgnoredForWholeClass;
  }

  public void setRequiredProperties(@NotNull Set<String> requiredProperties) {
    this.requiredProperties = requiredProperties;
  }

  public void setRequiredIfProperties(@NotNull Set<String> requiredIfProperties) {
    this.requiredIfProperties = requiredIfProperties;
  }

  public boolean isVersionMismatchIgnored(@NotNull String propertyName) {
    return versionMismatchIgnored.contains(propertyName);
  }

  public void setVersionMismatchIgnored(@NotNull Set<String> versionMismatchIgnored) {
    this.versionMismatchIgnored = versionMismatchIgnored;
  }

  public boolean hasParent(@NotNull TransientEntity e, @NotNull TransientChangesTracker tracker) {
    if (e.isNewOrTemporary() || parentChanged(tracker.getChangedLinksDetailed(e))) {
      checkAssociationEndsCreated();
      for (String childEnd : aggregationChildEnds) {
        if (AssociationSemantics.getToOne(e, childEnd) != null) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  public BasePersistentClass getInstance(Entity entity) {
    return (BasePersistentClass) EntityInstanceRegistry.getEntityInstance(entity, type);
  }

  private boolean parentChanged(Map<String, LinkChange> changedLinks) {
    if (changedLinks == null) {
      return false;
    }
    checkAssociationEndsCreated();
    for (String childEnd : aggregationChildEnds) {
      if (changedLinks.containsKey(childEnd)) {
        return true;
      }
    }
    return false;
  }

  private void checkAssociationEndsCreated() {
    if (associationEnds == null) {
      synchronized (this) {
        if (associationEnds == null) {
          if (externalAssociationEnds == null) {
            associationEnds = Collections.emptyMap();
            aggregationChildEnds = Collections.emptySet();
          } else {
            associationEnds = new HashMap<String, AssociationEndMetaData>(externalAssociationEnds.size());
            aggregationChildEnds = new HashSetDecorator<String>();
            for (final AssociationEndMetaData aemd : externalAssociationEnds) {
              associationEnds.put(aemd.getName(), aemd);
              if (AssociationEndType.ChildEnd.equals(aemd.getAssociationEndType())) {
                aggregationChildEnds.add(aemd.getName());
              }
            }
            externalAssociationEnds = null;
          }
        }
      }
    }
  }

  @Deprecated
  public void setUniqueProperties(@NotNull Set<String> uniqueProperties) {
    //throw new UnsupportedOperationException("Regenerate your persistent models.");
  }

}
