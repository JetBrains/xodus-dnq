package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.*;
import org.jetbrains.annotations.NotNull;

public class AssociationEndMetaDataImpl implements AssociationEndMetaData {

  private String name = null;
  private EntityMetaData emd = null;
  private String emdType = null;
  private AssociationEndCardinality cardinality = null;
  private String associationMetaDataName = null;
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
    this.emdType = oppositeEndEntityType.getType();
    this.cardinality = cardinality;
    this.setAssociationMetaDataInternal(associationMetaData);
    this.associationMetaDataName = ((AssociationMetaDataImpl) associationMetaData).getFullName();
    this.type = type;
    this.cascadeDelete = cascadeDelete;
    this.clearOnDelete = clearOnDelete;
    this.targetCascadeDelete = targetCascadeDelete;
    this.targetClearOnDelete = targetClearOnDelete;
  }

  void resolve(final ModelMetaDataImpl modelMetaData, final AssociationMetaData amd) {
      final EntityMetaData opposite = modelMetaData.getEntityMetaData(emdType);
      if (opposite == null) {
          throw new IllegalStateException("Can't find metadata for type: " + emdType + " from " + associationMetaDataName);
      }
      setOppositeEntityMetaDataInternal(opposite);
      setAssociationMetaDataInternal(amd);
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
  String getOppositeEntityMetaDataType() {
    return emdType;
  }

  @NotNull
  String getAssociationMetaDataName() {
    return associationMetaDataName;
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

  public void setOppositeEntityMetaDataType(@NotNull final String emdType) {
    this.emdType = emdType;
  }

  public void setCardinality(@NotNull AssociationEndCardinality cardinality) {
    this.cardinality = cardinality;
  }

  public void setAssociationMetaDataName(@NotNull String associationMetaDataName) {
    this.associationMetaDataName = associationMetaDataName;
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

  private void setAssociationMetaDataInternal(@NotNull AssociationMetaData associationMetaData) {
    this.associationMetaData = associationMetaData;
    ((AssociationMetaDataImpl)this.associationMetaData).addEnd(this);
  }

  private void setOppositeEntityMetaDataInternal(@NotNull final EntityMetaData emd) {
    this.emd = emd;
  }
}
