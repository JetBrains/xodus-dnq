package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 */
public class ModelMetaDataImpl implements ModelMetaData {

  private Map<String, EntityMetaData> typeToEntitiesMetaData = new HashMap<String, EntityMetaData>();
  private Map<String, Set<String>> typeToSubtypes = new HashMap<String, Set<String>>();

  public void setEntityMetaDatas(Set<EntityMetaData> entityMetaDatas) {
    for (EntityMetaData emd : entityMetaDatas) {
      if (typeToEntitiesMetaData.get(emd.getType()) != null) {
        throw new IllegalArgumentException("Duplicate entity [" + emd.getType() + "]");        
      }
      typeToEntitiesMetaData.put(emd.getType(), emd);
    }

    for (EntityMetaData emd : entityMetaDatas) {
      if (emd.getSuperType() != null) {
        Set<String> subtypes = typeToSubtypes.get(emd.getSuperType());

        if (subtypes == null) {
          subtypes = new HashSet<String>();
          typeToSubtypes.put(emd.getSuperType(), subtypes);
        }

        subtypes.add(emd.getType());
      }
    }
  }

  @Nullable
  public EntityMetaData getEntityMetaData(@NotNull String typeName) {
    return typeToEntitiesMetaData.get(typeName);
  }

  @NotNull
  public Iterable<EntityMetaData> getEntitiesMetaData() {
    return typeToEntitiesMetaData.values();
  }

  public void init() {
    initHierarchies();
  }

  private void initHierarchies() {
    for (EntityMetaData emd : typeToEntitiesMetaData.values()) {
      if (emd.getSuperType() != null) {
        if (typeToEntitiesMetaData.get(emd.getSuperType()) == null) {
          throw new IllegalStateException("Can't find metadata for type [" + emd.getSuperType() + "]");
        }

        emd.setWithinHierarchy(true);
      } else if (hasSubtypes(emd.getType())) {
        emd.setWithinHierarchy(true);
      }

      emd.setRootSuperType(getRootSuperType(emd.getType()));
    }
  }

  private boolean hasSubtypes(@NotNull String type) {
    for (EntityMetaData emd : typeToEntitiesMetaData.values()) {
      if (type.equals(emd.getSuperType())) {
        return true;
      }
    }

    return false;
  }

  @Nullable
  private Set<String> getAllSubtypes(@NotNull String type) {
    Set<String> res = typeToSubtypes.get(type);

    if (res != null) {
      for (String t: new HashSet<String>(res)) {
        Set<String> s = getAllSubtypes(t);
        if (s != null) {
          res.addAll(s);
        }
      }
    }

    return res;
  }

  private String getRootSuperType(@NotNull String type) {
    EntityMetaData emd = typeToEntitiesMetaData.get(type);

    if (emd.getSuperType() != null) {
      return getRootSuperType(emd.getSuperType());
    } else {
      return type;
    }
  }

}
