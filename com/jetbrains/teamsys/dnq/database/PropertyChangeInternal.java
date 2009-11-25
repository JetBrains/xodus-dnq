package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.PropertyChange;
import com.jetbrains.teamsys.database.PropertyChangeType;

/**
 * User: vadim
 */
public class PropertyChangeInternal extends PropertyChange {

  private Runnable change;

  PropertyChangeInternal(String propertyName, Comparable oldValue, PropertyChangeType changeType, Runnable change) {
    super(propertyName, oldValue, changeType);
    this.change = change;
  }

  /**
   * For internal use only!
   * @return
   */
  Runnable getChange() {
    return change;
  }
}
