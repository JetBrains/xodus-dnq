package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.AssociationMetaData;
import com.jetbrains.teamsys.database.AssociationType;
import com.jetbrains.teamsys.database.AssociationEndMetaData;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.ArrayList;

/**
 */
public class AssociationMetaDataImpl implements AssociationMetaData  {

  private AssociationType type;
  private List<AssociationEndMetaData> ends = new ArrayList<AssociationEndMetaData>();

  public void addEnd(@NotNull AssociationEndMetaData end) {
    this.ends.add(end);
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
