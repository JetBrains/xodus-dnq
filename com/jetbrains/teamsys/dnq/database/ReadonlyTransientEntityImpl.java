package com.jetbrains.teamsys.dnq.database;

import org.jetbrains.annotations.NotNull;
import com.jetbrains.teamsys.database.TransientStoreSession;
import com.jetbrains.teamsys.database.TransientEntityChange;
import com.jetbrains.teamsys.database.PropertyChange;
import com.jetbrains.teamsys.database.Entity;

import java.util.Map;
import java.io.InputStream;
import java.io.File;

public class ReadonlyTransientEntityImpl extends TransientEntityImpl {

  ReadonlyTransientEntityImpl(@NotNull TransientEntityChange change, @NotNull TransientStoreSession session) {
    super(((AbstractTransientEntity)change.getTransientEntity()).getPersistentEntityInternal(), session);

    populateCaches(change);
  }

  public boolean isReadonly() {
    return true;
  }

  private void populateCaches(TransientEntityChange change) {
    Map<String,PropertyChange> propertiesDetaled = change.getChangedPropertiesDetaled();
    
    if (propertiesDetaled != null) {
      for (String propertyName : propertiesDetaled.keySet()) {
        propertiesCache.put(propertyName, propertiesDetaled.get(propertyName).getOldValue());
      }
    }

    //TODO: fill links
  }

  @Override
  public <T extends Comparable> void setProperty(@NotNull String propertyName, @NotNull T value) {
    throw createReadonlyException();
  }

  @Override
  public void setBlob(@NotNull String blobName, @NotNull InputStream blob) {
    throw createReadonlyException();
  }

  @Override
  public void setBlob(@NotNull String blobName, @NotNull File file) {
    throw createReadonlyException();
  }

  @Override
  public void setBlobString(@NotNull String blobName, @NotNull String blobString) {
    throw createReadonlyException();
  }

  @Override
  public void setLink(@NotNull String linkName, @NotNull Entity target) {
    throw createReadonlyException();
  }

  @Override
  public void addLink(@NotNull String linkName, @NotNull Entity target) {
    throw createReadonlyException();
  }

  @Override
  public void deleteProperty(@NotNull String propertyName) {
    throw createReadonlyException();
  }

  @Override
  public void deleteBlob(@NotNull String blobName) {
    throw createReadonlyException();
  }

  @Override
  public void deleteLink(@NotNull String linkName, @NotNull Entity target) {
    throw createReadonlyException();
  }

  @Override
  public void deleteBlobString(@NotNull String blobName) {
    throw createReadonlyException();
  }

  @Override
  public void deleteLinks(@NotNull String linkName) {
    throw createReadonlyException();
  }

  @Override
  public void delete() {
    throw createReadonlyException();
  }

  private IllegalStateException createReadonlyException() {
    return new IllegalStateException("Entity is readonly.");
  }


}


