package com.jetbrains.teamsys.dnq.association;

import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.TransientEntity;
import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Implements aggregation assocations management.<p>
 * 1-1: project.[1]leader <-> user.[1]leaderInProject <p>
 * 1-n: project[0..n].issues <-> issue[1].project <p>
 * n-n: project[0..n].assignees <-> user[0..n].assigneeInProjects <p>
 */
public class AggregationAssociationSemantics {

  // name of property that stores name of link from parent to child
  private static final String CHILD_TO_PARENT_LINK_NAME = "__CHILD_TO_PARENT_LINK_NAME__";
  private static final String PARENT_TO_CHILD_LINK_NAME = "__PARENT_TO_CHILD_LINK_NAME__";

  /**
   * 1. parent.parentToChild = child <==> child.childToParent = parent
   * 2. parent.parentToChild = null <==> child.childToParent = null
   *
   * @param parent
   * @param parentToChildLinkName
   * @param childToParentLinkName
   * @param child
   */
  public static void setOneToOne(@Nullable Entity parent, @NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @Nullable Entity child) {
    if (parent == null && child != null) {
      // child.childToParent = null
      parent = AssociationSemantics.getToOne(child, childToParentLinkName);

      // parent == null means child has no parent with given name
      if (parent != null) {
        removeChildFromParent(child);
      }

    } else if (parent != null && child == null) {
      // parent.parentToChild = null;

      child = AssociationSemantics.getToOne(parent, parentToChildLinkName);

      // child == null means there was no association 
      if (child != null) {
        removeChildFromParent(child);
      }

    } else if (parent != null /*&& child != null*/) {
      // check old child
      Entity oldChild = AssociationSemantics.getToOne(parent, parentToChildLinkName);
      child = TransientStoreUtil.reattach((TransientEntity) child);

      if (!child.equals(oldChild)) {
        if (oldChild != null) {
          // remove old child from parent
          removeChildFromParent(oldChild);
        }

        // create new aggregation
        createOneToOne(parent, parentToChildLinkName, childToParentLinkName, child);
      }
    } else {
      throw new IllegalArgumentException("Both entities can't be null.");
    }
  }

  private static void createOneToOne(@NotNull Entity parent, @NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @NotNull Entity child) {
    // remove from current parent
    removeChildFromParent(child);

    UndirectedAssociationSemantics.setOneToOne(parent, parentToChildLinkName, childToParentLinkName, child);

    child.setProperty(PARENT_TO_CHILD_LINK_NAME, parentToChildLinkName);
    child.setProperty(CHILD_TO_PARENT_LINK_NAME, childToParentLinkName);
  }

  private static void removeChildFromParent(@NotNull Entity child) {
    String childToParentLinkName = child.getProperty(CHILD_TO_PARENT_LINK_NAME);
    String parentToChildLinkName = child.getProperty(PARENT_TO_CHILD_LINK_NAME);

    if (parentToChildLinkName == null && childToParentLinkName == null) {
      // child has no parent yet
      return;
    }

    assert parentToChildLinkName != null && childToParentLinkName != null;

    Entity parent = child.getLink(childToParentLinkName);
    assert parent != null;

    // remove link parent->child
    parent.deleteLink(parentToChildLinkName, child);

    // remove link child->parent
    child.deleteLink(childToParentLinkName, parent);

    child.deleteProperty(PARENT_TO_CHILD_LINK_NAME);
    child.deleteProperty(CHILD_TO_PARENT_LINK_NAME);
  }

  /**
   * parent.parentToChild.add(child)
   *
   * @param parent
   * @param parentToChildLinkName
   * @param childToParentLinkName
   * @param child
   */
  public static void createOneToMany(@NotNull Entity parent, @NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @NotNull Entity child) {
    // remove from current parent
    removeChildFromParent(child);

    UndirectedAssociationSemantics.createOneToMany(parent, parentToChildLinkName, childToParentLinkName, child);

    child.setProperty(PARENT_TO_CHILD_LINK_NAME, parentToChildLinkName);
    child.setProperty(CHILD_TO_PARENT_LINK_NAME, childToParentLinkName);
  }

  /**
   * parent.parentToChild.remove(child)
   *
   * @param parent
   * @param parentToChildLinkName
   * @param childToParentLinkName
   * @param child
   */
  public static void removeOneToMany(@NotNull Entity parent, @NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @NotNull Entity child) {
    //parent.parentToChild.remove(child)
    child = TransientStoreUtil.reattach((TransientEntity) child);
    String currentParentToChildLinkName = child.getProperty(PARENT_TO_CHILD_LINK_NAME);

    if (parentToChildLinkName.equals(currentParentToChildLinkName)) {
      removeChildFromParent(child);
    } else {
      //throw new IllegalArgumentException("Can't remove child from parent because child is not belong to parent.");
    }
  }

  /**
   * parent.parentToChild.clear
   *
   * @param parent
   * @param parentToChildLinkName
   */
  public static void clearOneToMany(@NotNull Entity parent, @NotNull String parentToChildLinkName) {
    //parent.parentToChild.clear

    parent = TransientStoreUtil.reattach((TransientEntity) parent);
    for (Entity child : AssociationSemantics.getToManyList(parent, parentToChildLinkName)) {
      removeChildFromParent(child);
    }
  }

  /**
   * child.childToParent = parent
   * child.childToParent = null
   *
   * @param parent
   * @param parentToChildLinkName
   * @param childToParentLinkName
   * @param child
   */
  public static void setManyToOne(@Nullable Entity parent, @NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @NotNull Entity child) {
    parent = TransientStoreUtil.reattach((TransientEntity) parent);
    child = TransientStoreUtil.reattach((TransientEntity) child);

    if (parent == null) {
      // child.childToParent = null
      parent = AssociationSemantics.getToOne(child, childToParentLinkName);
      // parent == null means there was no link
      if (parent != null) {
        removeChildFromParent(child);
      }
    } else {
      // child.childToParent = parent

      // check old parent
      Entity oldParent = AssociationSemantics.getToOne(child, childToParentLinkName);

      if (!parent.equals(oldParent)) {

        // remove from current parent
        removeChildFromParent(child);

        UndirectedAssociationSemantics.createOneToMany(parent, parentToChildLinkName, childToParentLinkName, child);

        child.setProperty(PARENT_TO_CHILD_LINK_NAME, parentToChildLinkName);
        child.setProperty(CHILD_TO_PARENT_LINK_NAME, childToParentLinkName);
      }
    }
  }

  @Nullable
  public static Entity getParent(@NotNull Entity child) {
    String childToParentLinkName = child.getProperty(CHILD_TO_PARENT_LINK_NAME);
    String parentToChildLinkName = child.getProperty(PARENT_TO_CHILD_LINK_NAME);

    if (parentToChildLinkName == null && childToParentLinkName == null) {
      return null;
    }

    assert parentToChildLinkName != null && childToParentLinkName != null;

    Entity parent = child.getLink(childToParentLinkName);
    assert parent != null;

    return parent;
  }

}

