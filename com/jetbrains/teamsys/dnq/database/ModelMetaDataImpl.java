package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.EntityMetaData;
import com.jetbrains.teamsys.database.ModelMetaData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 */
public class ModelMetaDataImpl implements ModelMetaData {

  private Map<String, EntityMetaData> typeToEntityMetaDatas = new HashMap<String, EntityMetaData>();

  public void setEntityMetaDatas(Set<EntityMetaData> entityMetaDatas) {
    for (final EntityMetaData emd : entityMetaDatas) {
      final String type = emd.getType();
      if (typeToEntityMetaDatas.get(type) != null) {
        throw new IllegalArgumentException("Duplicate entity [" + type + "]");
      }
      typeToEntityMetaDatas.put(type, emd);
    }

    for (final EntityMetaData emd : entityMetaDatas) {
      final String superType = emd.getSuperType();
      if (superType != null) {
        final EntityMetaData superEmd = typeToEntityMetaDatas.get(superType);
        if (superEmd == null) {
          throw new IllegalArgumentException("No entity metadata for super type [" + superType + "]");
        }
        superEmd.addSubType(emd.getType());
      }
    }
  }

  @Nullable
  public EntityMetaData getEntityMetaData(@NotNull String typeName) {
    return typeToEntityMetaDatas.get(typeName);
  }

  @NotNull
  public Iterable<EntityMetaData> getEntitiesMetaData() {
    return typeToEntityMetaDatas.values();
  }

  public void init() {
    initHierarchies();
  }

  private void initHierarchies() {
    for (final EntityMetaData emd : typeToEntityMetaDatas.values()) {
      final String type = emd.getType();
      final String superType = emd.getSuperType();
      if (superType != null) {
        if (typeToEntityMetaDatas.get(superType) == null) {
          throw new IllegalStateException("Can't find metadata for type [" + superType + "]");
        }

        emd.setWithinHierarchy(true);
      } else if (emd.hasSubTypes()) {
        emd.setWithinHierarchy(true);
      }

      emd.setRootSuperType(getRootSuperType(type));
    }
  }

  private String getRootSuperType(@NotNull String type) {
    final String superType = typeToEntityMetaDatas.get(type).getSuperType();
    if (superType != null) {
      return getRootSuperType(superType);
    } else {
      return type;
    }
  }

}
