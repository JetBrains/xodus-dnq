package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.ModelMetaData;
import com.jetbrains.teamsys.database.EntityMetaData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DynamicCastOperations {

  private DynamicCastOperations() {
  }

  @Nullable
  public static Entity as(@NotNull ModelMetaData mmd, @Nullable Object e, @NotNull String asType) {
    Entity result = null;
    if (e != null && e instanceof Entity && _instanceof(mmd, e, asType)) {
      result = (Entity) e;
    }
    return result;
  }

  public static boolean _instanceof(@NotNull ModelMetaData mmd, @Nullable Object e, @NotNull String asType) {
    if (e instanceof Entity) {
      final Entity entity = (Entity) e;
      String type = entity.getType();
      while (!type.equals(asType)) {
        EntityMetaData emd = mmd.getEntityMetaData(type);
        if (emd == null) return false;
        type = emd.getSuperType();
        if (type == null) return false;
      }
      return true;
    }
    return false;
  }
}
