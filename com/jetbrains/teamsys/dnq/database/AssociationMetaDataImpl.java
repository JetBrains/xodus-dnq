package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.AssociationMetaData;
import jetbrains.exodus.database.AssociationType;
import jetbrains.exodus.database.AssociationEndMetaData;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.BeanNameAware;

import java.util.List;
import java.util.ArrayList;

/**
 */
public class AssociationMetaDataImpl implements AssociationMetaData, BeanNameAware {

  private AssociationType type;
  private List<AssociationEndMetaData> ends = new ArrayList<AssociationEndMetaData>(2);
  private String fullName;

  public String getFullName() {
    return fullName;
  }

  public void setBeanName(final String beanName) {
    this.fullName = beanName;
  }

  public void addEnd(@NotNull AssociationEndMetaData end) {
    if (ends.isEmpty() || (ends.size() < 2 && ends.get(0) != end)) {
      this.ends.add(end);
    }
  }

  public void setType(@NotNull AssociationType type) {
    this.type = type;
  }

  @NotNull
  public AssociationType getType() {
    return type;
  }

  @NotNull
  public AssociationEndMetaData getOppositeEnd(@NotNull AssociationEndMetaData end) {
    if (AssociationType.Directed.equals(type)) {
      // directed association
      throw new IllegalStateException("Directed association has no opposite end.");
    }

    if (ends.size() != 1 && ends.size() != 2) {
      throw new IllegalStateException("Incomplete association.");
    }

    return end == ends.get(0) ? ends.get(1) : ends.get(0);
  }
}
