package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.decorators.HashMapDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.HashSetDecorator;
import com.jetbrains.teamsys.core.dataStructures.hash.HashSet;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

public class EntityMetaDataImpl implements EntityMetaData {

  private String type = null;
  private String superType = null;
  private Runnable initializer = null;
  private InstanceRef<? extends BasePersistentClass> instanceRef = null;
  private boolean removeOrphan = true;
  private Set<String> subTypes = new HashSetDecorator<String>();
  private Map<String, AssociationEndMetaData> associationEnds = new HashMapDecorator<String, AssociationEndMetaData>();
  private Set<String> aggregationChildEnds = new HashSetDecorator<String>();
  private Set<String> uniqueProperties = new HashSetDecorator<String>();
  private Set<String> requiredProperties = new HashSetDecorator<String>();
  private Set<String> requiredIfProperties = new HashSetDecorator<String>();
  private Set<String> historyIgnoredFields = new HashSetDecorator<String>();
  private Set<String> versionMismatchIgnored = new HashSetDecorator<String>();
  private Map<String, Set<String>> incomingAssociations = null;
  private boolean versionMismatchIgnoredForWholeClass = false;

  public void setType(String type) {
    this.type = type;
  }

  public void setSuperType(String superType) {
    this.superType = superType;
  }

  public boolean hasSubTypes() {
    return !subTypes.isEmpty();
  }

  public Iterable<String> getSubTypes() {
    return subTypes;
  }

  public void addSubType(@NotNull String type) {
    subTypes.add(type);
  }

  public void setInstanceRef(InstanceRef instanceRef) {
    this.instanceRef = instanceRef;
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
    for (AssociationEndMetaData ae : associationEnds) {
      this.associationEnds.put(ae.getName(), ae);
    }
    initAssociationChildEnds();
  }

  private void initAssociationChildEnds() {
    for (AssociationEndMetaData aemd : associationEnds.values()) {
      if (AssociationEndType.ChildEnd.equals(aemd.getAssociationEndType())) {
        aggregationChildEnds.add(aemd.getName());
      }
    }
  }

  @NotNull
  public String getType() {
    return type;
  }

  @Nullable
  public String getSuperType() {
    return superType;
  }

  @NotNull
  public AssociationEndMetaData getAssociationEndMetaData(@NotNull String name) {
    AssociationEndMetaData res = associationEnds.get(name);

    if (res == null) {
      throw new IllegalArgumentException("Association end with name [" + name + "] is not found in metadata.");
    }

    return res;
  }

  @NotNull
  public Iterable<AssociationEndMetaData> getAssociationEndsMetaData() {
    return associationEnds.values();
  }

  public boolean getHasHistory(Entity e) {
    return instanceRef.getInstance(e).evaluateWithHistory(e);
  }

  public void callDestructor(Entity e) {
    instanceRef.getInstance(e).destructor(e);
  }

  public boolean getRemoveOrphan() {
    return removeOrphan;
  }

  public boolean hasAggregationChildEnds() {
    return !aggregationChildEnds.isEmpty();
  }

  public Set<String> getAggregationChildEnds() {
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
              if (typeWithSubTypes.contains(aemd.getEntityMetaData().getType())) {
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
  public Set<String> getUniqueProperties() {
    return uniqueProperties;
  }

  public void setUniqueProperties(@NotNull Set<String> uniqueProperties) {
    this.uniqueProperties = uniqueProperties;
  }

  @NotNull
  public Set<String> getRequiredProperties() {
    return requiredProperties;
  }


  @NotNull
  public Set<String> getRequiredIfProperties(Entity e) {
    Set<String> result = new HashSetDecorator<String>();
    for (String property : requiredIfProperties) {
      if (instanceRef.getInstance(e).isPropertyRequired(property, e)) {
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
      for (String childEnd : aggregationChildEnds) {
        if (AssociationSemantics.getToOne(e, childEnd) != null) {
          return true;
        }
      }
      return false;
    }

    return true;
  }

  private boolean parentChanged(Map<String, LinkChange> changedLinks) {
    if (changedLinks == null) {
      return false;
    }

    for (String childEnd : aggregationChildEnds) {
      if (changedLinks.containsKey(childEnd)) {
        return true;
      }
    }

    return false;
  }

}
