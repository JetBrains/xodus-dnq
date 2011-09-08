package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.IndexField;

/**
 * Date: Nov 19, 2009
 */
public class IndexFieldImpl implements IndexField {

  private boolean property;
  private String name;
  private String ownerEnityType;

  public IndexFieldImpl() {
  }

  public boolean isProperty() {
    return property;
  }

  public void setProperty(boolean property) {
    this.property = property;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getOwnerEnityType() {
    return ownerEnityType;
  }

  public void setOwnerEnityType(String ownerEnityType) {
    this.ownerEnityType = ownerEnityType;
  }

  @Override
  public String toString() {
    return ownerEnityType + "." + name;
  }
}
