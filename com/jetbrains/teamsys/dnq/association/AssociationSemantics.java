package com.jetbrains.teamsys.dnq.association;

import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.EntityIterable;
import com.jetbrains.teamsys.database.TransientEntity;
import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.ArrayList;

/**
 */
public class AssociationSemantics {

  private static final Log log = LogFactory.getLog(AssociationSemantics.class);

  /**
   * To one association end getter.
   * Supports nullable objects - input entity may be null
   *
   * @param e
   * @param linkName
   * @return
   */
  @Nullable
  public static Entity getToOne(@Nullable Entity e, @NotNull String linkName) {
    // nullable objects support 
    if (e == null) {
      return null;
    }

    e = TransientStoreUtil.reattach((TransientEntity) e);
    return e.getLink(linkName);
  }

  @NotNull
  public static EntityIterable getToMany(@NotNull Entity e, @NotNull String linkName) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    return e.getLinks(linkName);
  }

  /**
   * Returns copy of {@link #getToMany(com.jetbrains.teamsys.database.Entity, String)} iterable
   * @param e
   * @param linkName
   * @return
   */
  @NotNull
  public static List<Entity> getToManyList(@NotNull Entity e, @NotNull String linkName) {
    List<Entity> res = new ArrayList<Entity>();

    for (Entity entity : getToMany(e, linkName)) {
      res.add(entity);
    }
    
    return res;
  }

  /**
   * Returns persistent iterable if possible 
   * @param e
   * @param linkName
   * @return
   */
  @NotNull
  public static EntityIterable getToManyPersistentIterable(@NotNull Entity e, @NotNull String linkName) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    // can't return persistent iterable for new transient entity
    if (((TransientEntity)e).isNewOrTemporary()) {
      //throw new IllegalStateException("1111");
      return e.getLinks(linkName);
    }
    
    return ((TransientEntity) e).getPersistentEntity().getLinks(linkName);
  }

  /**
   * Returns links size
   *
   * @param e
   * @param linkName
   * @return
   */
  public static long getToManySize(@NotNull Entity e, String linkName) {
    if (e instanceof TransientEntity) {
      e = TransientStoreUtil.reattach((TransientEntity) e);

      return ((TransientEntity) e).getLinksSize(linkName);
    }

    return e.getLinks(linkName).size();
  }

}
