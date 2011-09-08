package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.EntityId;
import jetbrains.exodus.database.TransientEntityId;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Date: 05.02.2007
 * Time: 17:34:24
 *
 * @author Vadim.Gurov
 */
class TransientEntityIdImpl implements TransientEntityId {

  private static final AtomicInteger HASH_CODE_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);

  private final int hashCode;

  TransientEntityIdImpl() {
    this.hashCode = HASH_CODE_GENERATOR.getAndIncrement();
  }

  private TransientEntityIdImpl(int hashCode) {
    this.hashCode = hashCode;
  }

  public int getTypeId() {
    return 0;
  }

  public long getLocalId() {
    return hashCode;
  }

  public int compareTo(EntityId o) {
    throw new UnsupportedOperationException("Not supported by transient entity id");
  }

  @NotNull
  public String toString() {
    return Integer.toString(hashCode);
  }

  public int hashCode() {
    return hashCode;
  }

  public boolean equals(Object obj) {
    if (!(obj instanceof TransientEntityIdImpl)) {
      return false;
    }

    return this.hashCode == ((TransientEntityIdImpl)obj).hashCode;
  }

  static TransientEntityId fromString(String id) {
    try {
      return new TransientEntityIdImpl(Integer.parseInt(id));
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid structure of entity id representation [" + id + "]");
    }
  }

}
