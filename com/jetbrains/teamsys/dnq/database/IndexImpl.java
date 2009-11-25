package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.Index;
import com.jetbrains.teamsys.database.IndexField;

import java.util.List;
import java.util.ArrayList;

/**
 * Date: Nov 19, 2009
 */
public class IndexImpl implements Index {

  private List<IndexField> fields;

  public IndexImpl() {
  }

  public void setFields(List<IndexField> fields) {
    this.fields = fields;
  }

  public List<IndexField> getFields() {
    return fields;
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
      sb.append(f.getName());
    }

    return sb.toString();
  }

}
