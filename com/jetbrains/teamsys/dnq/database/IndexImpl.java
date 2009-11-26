package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.Index;
import com.jetbrains.teamsys.database.IndexField;
import com.jetbrains.teamsys.database.EntityIterable;
import com.jetbrains.teamsys.database.ModelMetaData;
import com.jetbrains.teamsys.core.dataStructures.hash.HashSet;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;

import jetbrains.teamsys.dnq.runtime.util.DnqUtils;

/**
 * Date: Nov 19, 2009
 */
public class IndexImpl implements Index {

  private List<IndexField> fields;
  private ModelMetaData modelMetaData; 

  public IndexImpl() {
  }

  public void setFields(List<IndexField> fields) {
    this.fields = fields;
  }

  public List<IndexField> getFields() {
    return fields;
  }

  public Set<String> getEntityTypesToIndex() {
    // me and inheritors
    Set<String> res = new HashSet<String>();
    for (IndexField f: fields) {
      String enityType = f.getOwnerEnityType();
      res.add(enityType);
      DnqUtils.getModelMetaData().getEntityMetaData(enityType).getAllSubTypes(DnqUtils.getModelMetaData());
    }
    return res;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();

    boolean first = true;
    for (IndexField f: fields) {
      if (!first) {
        sb.append(", ");
      } else {
        first = false;
      }
      sb.append(f.toString());
    }

    return sb.toString();
  }

}
