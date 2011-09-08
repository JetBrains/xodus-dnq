package com.jetbrains.teamsys.dnq.association;

import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.TransientEntity;
import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Implements undirected assocations management.<p>
 * 1-1: project.[1]leader <-> user.[1]leaderInProject <p>
 * 1-n: project[0..n].issues <-> issue[1].project <p>
 * n-n: project[0..n].assignees <-> user[0..n].assigneeInProjects <p>
 */
public class UndirectedAssociationSemantics {

  /**
   * 1. e1.e1Toe2LinkName = e2 <==> e2.e2Toe1LinkName = e1;
   * 2. e2.e2Toe1LinkName = null <==> e1.e1Toe1LinkName = null
   *
   * @param e1
   * @param e1Toe2LinkName
   * @param e2Toe1LinkName
   * @param e2
   */
  public static void setOneToOne(@Nullable Entity e1, @NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName,  @Nullable Entity e2) {
    if (e1 == null && e2 == null) {
      throw new IllegalArgumentException("Both entities can't be null.");
    } else if (e1 == null && e2 != null) {
      // e2.e2Toe1LinkName = null;
      e1 = AssociationSemantics.getToOne(e2, e2Toe1LinkName);

      // e1 == null means there was no association between e1 and e2
      if (e1 != null) {
        DirectedAssociationSemantics.setToOne(e1, e1Toe2LinkName, null);
        DirectedAssociationSemantics.setToOne(e2, e2Toe1LinkName, null);
      }
    } else if (e1 != null && e2 == null) {
      // e1.e1Toe1LinkName = null;

      e2 = AssociationSemantics.getToOne(e1, e1Toe2LinkName);

      // e2 == null means there was no association between e1 and e2
      if (e2 != null) {
        DirectedAssociationSemantics.setToOne(e1, e1Toe2LinkName, null);
        DirectedAssociationSemantics.setToOne(e2, e2Toe1LinkName, null);
      }
    } else {
      // e1.e1Toe2LinkName = e2 or e2.e2Toe1LinkName = e1
      DirectedAssociationSemantics.setToOne(e1, e1Toe2LinkName, e2);
      DirectedAssociationSemantics.setToOne(e2, e2Toe1LinkName, e1);
    }
  }

  /**
   * one.oneToManyLinkName.add(many)
   * 
   * @param one
   * @param many
   * @param oneToManyLinkName
   * @param manyToOneLinkName
   */
  public static void createOneToMany(@NotNull Entity one, @NotNull String oneToManyLinkName, @NotNull String manyToOneLinkName, @NotNull Entity many) {
    DirectedAssociationSemantics.createToMany(one, oneToManyLinkName, many);
    DirectedAssociationSemantics.setToOne(many, manyToOneLinkName, one);
  }

  /**
   * one.oneToManyLinkName.remove(many)
   *
   * @param one
   * @param many
   * @param oneToManyLinkName
   * @param manyToOneLinkName
   */
  public static void removeOneToMany(@NotNull Entity one, @NotNull String oneToManyLinkName, @NotNull String manyToOneLinkName, @NotNull Entity many) {
    //one.oneToManyLinkName.remove(many)
    DirectedAssociationSemantics.removeToMany(one, oneToManyLinkName, many);
    DirectedAssociationSemantics.setToOne(many, manyToOneLinkName, null);
  }

  /**
   * one.oneToManyLinkName.clear
   *
   * @param one
   * @param oneToManyLinkName
   * @param manyToOneLinkName
   */
  public static void clearOneToMany(@NotNull Entity one, @NotNull String oneToManyLinkName, @NotNull String manyToOneLinkName) {
    //one.oneToManyLinkName.removeAll

    for (Entity many: AssociationSemantics.getToManyList(one, oneToManyLinkName)) {
      DirectedAssociationSemantics.removeToMany(one, oneToManyLinkName, many);
      DirectedAssociationSemantics.setToOne(many, manyToOneLinkName, null);
    }
  }

  /**
   * many.manyToOneLinkName = one
   * many.manyToOneLinkName = null
   *
   * @param one
   * @param oneToManyLinkName
   * @param manyToOneLinkName
   * @param many
   */
  public static void setManyToOne(@Nullable Entity one, @NotNull String oneToManyLinkName, @NotNull String manyToOneLinkName, @NotNull Entity many) {
    if (one == null) {
      // many.manyToOneLinkName = null
      one = AssociationSemantics.getToOne(many, manyToOneLinkName);
      // one == null means there was no link between  many and one
      if (one != null) {
        DirectedAssociationSemantics.removeToMany(one, oneToManyLinkName, many);
        DirectedAssociationSemantics.setToOne(many, manyToOneLinkName, null);
      }
    } else {
      Entity oldOne = AssociationSemantics.getToOne(many, manyToOneLinkName);
      one = TransientStoreUtil.reattach((TransientEntity)one);
      if (oldOne != null && oldOne.equals(one)) {
        // association already exists
        return;
      }

      if (oldOne != null) {
        // remove from oldOne
        DirectedAssociationSemantics.removeToMany(oldOne, oneToManyLinkName, many);
      }

      // create new associaiton ends
      DirectedAssociationSemantics.createToMany(one, oneToManyLinkName, many);
      DirectedAssociationSemantics.setToOne(many, manyToOneLinkName, one);
    }
  }

  /**
   * e1.e1Toe2LinkName.add(e2) <==> e2.e2Toe1LinkName.add(e1)
   *
   * @param e1
   * @param e2
   * @param e1Toe2LinkName
   * @param e2Toe1LinkName
   */
  public static void createManyToMany(@NotNull Entity e1, @NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName, @NotNull Entity e2) {
    DirectedAssociationSemantics.createToMany(e1, e1Toe2LinkName, e2);
    DirectedAssociationSemantics.createToMany(e2, e2Toe1LinkName, e1);
  }

  /**
   * e1.e1Toe2LinkName.remove(e2) <==> e2.e2Toe1LinkName.remove(e1)
   *
   * @param e1
   * @param e2
   * @param e1Toe2LinkName
   * @param e2Toe1LinkName
   */
  public static void removeManyToMany(@NotNull Entity e1, @NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName, @NotNull Entity e2) {
    DirectedAssociationSemantics.removeToMany(e1, e1Toe2LinkName, e2);
    DirectedAssociationSemantics.removeToMany(e2, e2Toe1LinkName, e1);
  }

  /**
   * e1.e1Toe2LinkName.clear <==> e2.e2Toe1LinkName.clear
   *
   * @param e1
   * @param e1Toe2LinkName
   * @param e2Toe1LinkName
   */
  public static void clearManyToMany(@NotNull Entity e1, @NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName) {
    for (Entity e2: AssociationSemantics.getToManyList(e1, e1Toe2LinkName)) {
      DirectedAssociationSemantics.removeToMany(e1, e1Toe2LinkName, e2);
      DirectedAssociationSemantics.removeToMany(e2, e2Toe1LinkName, e1);
    }
  }

}
