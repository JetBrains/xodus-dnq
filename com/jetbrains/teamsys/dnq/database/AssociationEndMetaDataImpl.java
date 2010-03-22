package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import org.jetbrains.annotations.NotNull;

public class AssociationEndMetaDataImpl implements AssociationEndMetaData {

  private String name = null;
  private EntityMetaData emd = null;
  private AssociationEndCardinality cardinality = null;
  private AssociationMetaData associationMetaData = null;
  private AssociationEndType type = null;
  private boolean cascadeDelete = false;
  private boolean clearOnDelete = false;
  private boolean targetCascadeDelete = false;
  private boolean targetClearOnDelete = false;

    public AssociationEndMetaDataImpl() {
    }

    public AssociationEndMetaDataImpl(AssociationMetaData associationMetaData, String name,
                                      EntityMetaData oppositeEndEntityType, AssociationEndCardinality cardinality, 
                                      AssociationEndType type,
                                      boolean cascadeDelete, boolean clearOnDelete,
                                      boolean targetCascadeDelete, boolean targetClearOnDelete) {
        this.name = name;
        this.emd = oppositeEndEntityType;
        this.cardinality = cardinality;
        this.setAssociationMetaData(associationMetaData);
        this.type = type;
        this.cascadeDelete = cascadeDelete;
        this.clearOnDelete = clearOnDelete;
        this.targetCascadeDelete = targetCascadeDelete;
        this.targetClearOnDelete = targetClearOnDelete;
    }

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

  public boolean getTargetCascadeDelete() {
    return targetCascadeDelete;
  }

  public boolean getTargetClearOnDelete() {
    return targetClearOnDelete;
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
    ((AssociationMetaDataImpl)this.associationMetaData).addEnd(this);
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

  public void setTargetCascadeDelete(boolean cascadeDelete) {
    this.targetCascadeDelete = cascadeDelete;
  }

  public void setTargetClearOnDelete(boolean clearOnDelete) {
    this.targetClearOnDelete = clearOnDelete;
  }
}
