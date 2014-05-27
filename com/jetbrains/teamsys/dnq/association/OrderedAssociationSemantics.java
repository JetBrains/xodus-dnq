package com.jetbrains.teamsys.dnq.association;

import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.database.TransientEntity;
import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import org.jetbrains.annotations.Nullable;

/**
 * User: Maxim Mazin
 */
public class OrderedAssociationSemantics {
  public static boolean compare(@Nullable Entity left, @Nullable Entity right, String orderPropertyName, int cmp) {
    left = TransientStoreUtil.reattach((TransientEntity) left);
    right = TransientStoreUtil.reattach((TransientEntity) right);

    int cmpResult;
    if (left == null || right == null) {
      if (left != null) {
        cmpResult = 1;
      } else if (right != null) {
        cmpResult = -1;
      } else {
        cmpResult = 0;
      }
    } else {
      Comparable leftOrder = left.getProperty(orderPropertyName);
      Comparable rightOrder = right.getProperty(orderPropertyName);
      cmpResult = leftOrder.compareTo(rightOrder);
    }

    switch (cmp) {
      case 1: return cmpResult >= 0;
      case 2: return cmpResult < 0;
      case 3: return cmpResult <= 0;
      default: return cmpResult > 0;
    }
  }


  public static void swap(@Nullable Entity left, @Nullable Entity right, String orderPropertyName) {
    left = TransientStoreUtil.reattach((TransientEntity) left);
    right = TransientStoreUtil.reattach((TransientEntity) right);

    if (left != null && right != null) {
      Comparable leftOrder = left.getProperty(orderPropertyName);
      Comparable rightOrder = right.getProperty(orderPropertyName);

      if (leftOrder != null && rightOrder != null) {
        left.setProperty(orderPropertyName, rightOrder);
        right.setProperty(orderPropertyName, leftOrder);     
      }
    }
  }
}
