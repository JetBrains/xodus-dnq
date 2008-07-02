package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

public class EntityMetaDataImpl implements EntityMetaData {

  private String type = null;
  private String superType = null;
  private String rootSuperType = null;
  private boolean withinHierarchy = false;
  private DestructorRef destructor = null;
  private Runnable initializer = null;
  private boolean history = false;
  private boolean removeOrphan = true;
  private Map<String, AssociationEndMetaData> associationEnds = new HashMap<String, AssociationEndMetaData>();
  private Set<String> aggregationChildEnds = new HashSet<String>(3);
  private Set<String> uniqueProperties = new HashSet<String>(1);
  private Set<String> requiredProperties = new HashSet<String>(1);
  private Set<String> historyIgnoredFields = new HashSet<String>(1);
  private Set<String> versionMismatchIgnored = new HashSet<String>(1);
  private boolean versionMismatchIgnoredForWholeClass = false;

  public void setType(String type) {
    this.type = type;
  }

  public void setSuperType(String superType) {
    this.superType = superType;
  }

  @NotNull
  public String getRootSuperType() {
    return rootSuperType;
  }

  public void setRootSuperType(@NotNull String rootSuperType) {
    this.rootSuperType = rootSuperType;
  }

  public void setWithinHierarchy(boolean withinHierarchy) {
    this.withinHierarchy = withinHierarchy;
  }

  public void setDestructor(DestructorRef destructor) {
    this.destructor = destructor;
  }

  public void setInitializer(Runnable initializer) {
    this.initializer = initializer;
  }

  public Runnable getInitializer() {
    return initializer;
  }

  public void setHistory(boolean history) {
    this.history = history;
  }

  public void setHistoryIgnoredFields(Set<String> historyIgnoredFields) {
    this.historyIgnoredFields = historyIgnoredFields;
  }

  public boolean changesReflectHistory(TransientEntity e, TransientChangesTracker tracker) {
    Set<String> changedProperties = tracker.getChangedProperties(e);
    if (changedProperties != null) {
      for (String field: changedProperties) {
        if (!historyIgnoredFields.contains(field)) {
          return true;
        }
      }
    }

    Set<String> changedLinks = tracker.getChangedLinks(e);
    if (changedLinks != null) {
      for (String field: changedLinks) {
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
    aggregationChildEnds = new HashSet<String>();
    for (AssociationEndMetaData aemd: associationEnds.values()) {
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

  public boolean getWithinHierarchy() {
    return withinHierarchy;
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

  public boolean getHasHistory() {
    return history;
  }

  @Nullable
  public DestructorRef getDestructor() {
    return destructor;
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

  public boolean isVersionMismatchIgnoredForWholeClass() {
    return versionMismatchIgnoredForWholeClass;
  }

  public void setVersionMismatchIgnoredForWholeClass(boolean versionMismatchIgnoredForWholeClass) {
    this.versionMismatchIgnoredForWholeClass = versionMismatchIgnoredForWholeClass;
  }

  public void setRequiredProperties(@NotNull Set<String> requiredProperties) {
    this.requiredProperties = requiredProperties;
  }

  public boolean isVersionMismatchIgnored(@NotNull String propertyName) {
    return versionMismatchIgnored.contains(propertyName); 
  }

  public void setVersionMismatchIgnored(@NotNull Set<String> versionMismatchIgnored) {
    this.versionMismatchIgnored = versionMismatchIgnored; 
  }

  public boolean hasParent(@NotNull TransientEntity e, @NotNull TransientChangesTracker tracker) {
    if (e.isNewOrTemporary() || parentChanged(tracker.getChangedLinks(e))) {
      for (String childEnd : aggregationChildEnds) {
        if (AssociationSemantics.getToOne(e, childEnd) != null) {
          return true;
        }
      }
      return false;
    }

    return true;
  }

  private boolean parentChanged(Set<String> changedLinks) {
    if(changedLinks == null) {
      return false;
    }

    for (String childEnd : aggregationChildEnds) {
      if (changedLinks.contains(childEnd)) {
        return true;
      }
    }

    return false;
  }

}
