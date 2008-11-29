package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import org.jetbrains.annotations.NotNull;

public class AssociationEndMetaDataImpl implements AssociationEndMetaData {

  private String name;
  private EntityMetaData emd;
  private AssociationEndCardinality cardinality;
  private AssociationMetaData associationMetaData;
  private AssociationEndType type;
  private boolean cascadeDelete = false;
  private boolean clearOnDelete = false;

  @NotNull
  public String getName() {
    return name;
  }

  @NotNull
  public EntityMetaData getOppositeEntityMetaData() {
    return emd;
  }

  @NotNull
  public AssociationEndCardinality getCardinality() {
    return cardinality;
  }

  @NotNull
  public AssociationMetaData getAssociationMetaData() {
    return associationMetaData;
  }

  @NotNull
  public AssociationEndType getAssociationEndType() {
    return type;
  }

  public boolean getCascadeDelete() {
    return cascadeDelete;
  }

  public boolean getClearOnDelete() {
    return clearOnDelete;
  }

  public void setName(@NotNull String name) {
    this.name = name;
  }

  public void setOppositeEntityMetaData(@NotNull final EntityMetaData emd) {
    this.emd = emd;
  }

  public void setCardinality(@NotNull AssociationEndCardinality cardinality) {
    this.cardinality = cardinality;
  }

  public void setAssociationMetaData(@NotNull AssociationMetaData associationMetaData) {
    this.associationMetaData = associationMetaData;
    associationMetaData.addEnd(this);
  }

  public void setAssociationEndType(@NotNull AssociationEndType type) {
    this.type = type;
  }

  public void setCascadeDelete(boolean cascadeDelete) {
    this.cascadeDelete = cascadeDelete;
  }

  public void setClearOnDelete(boolean clearOnDelete) {
    this.clearOnDelete = clearOnDelete;
  }
}
