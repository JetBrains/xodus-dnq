package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.database.EntityMetaData;
import com.jetbrains.teamsys.database.ModelMetaData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
}
