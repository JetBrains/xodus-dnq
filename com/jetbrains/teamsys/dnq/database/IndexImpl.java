package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.Index;
import com.jetbrains.teamsys.database.IndexField;

import java.util.List;

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
  
}
