package com.jetbrains.teamsys.dnq.association;

import com.jetbrains.teamsys.core.crypto.MessageDigestUtil;
import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.PropertyChange;
import com.jetbrains.teamsys.database.TransientEntity;
import com.jetbrains.teamsys.database.TransientStoreSession;
import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.Map;

/**
 */
public class PrimitiveAssociationSemantics {

  private static float FLOAT_PRECISION = 0.0001f;
  private static double DOUBLE_PRECISION = 0.0000001f;

  /**
   * Simple property getter.
   * Supports nullable objects - returns "null value" if input entity is null
   *
   * @param e
   * @param propertyName
   * @return
   */
  @Nullable
  public static Object get(@Nullable Entity e, @NotNull String propertyName, @Nullable Object nullValue) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    if (e == null) {
      return nullValue;
    }

    //noinspection ConstantConditions
    return e.getProperty(propertyName);
  }

  @Nullable
  public static Object getOldValue(@NotNull TransientEntity e, @NotNull String propertyName, @NotNull Class propertyType, @Nullable Object nullValue) {
    Object res = getOldValue(e, propertyName, nullValue);
    return res == null ? getPropertyNullValue(propertyType) : res;
  }

   /**
   * Property old value getter, similar as #get.
   * Supports nullable objects - returns "null value" if input entity is null
   *
   * @param e
   * @param propertyName
   * @return
   */
  @Nullable
  public static Object getOldValue(@NotNull TransientEntity e, @NotNull String propertyName, @Nullable Object nullValue) {
    e = TransientStoreUtil.reattach(e);

    if (e == null) {
      return null;
    }

    Map<String,PropertyChange> propertiesDetailed = e.getTransientStoreSession().getTransientChangesTracker().getChangedPropertiesDetailed(e);
    if (propertiesDetailed != null) {
      PropertyChange pc = propertiesDetailed.get(propertyName);
      if (pc != null) {
        return pc.getOldValue();
      }
    }

    return get(e, propertyName, nullValue);
  }


  /**
   * Simple property getter.
   * Supports nullable objects - returns "null value" if input entity is null
   *
   * @param e
   * @param propertyName
   * @return
   */
  @Nullable
  public static Object get(@Nullable Entity e, @NotNull String propertyName, @NotNull Class propertyType, @Nullable Object nullValue) {
    Object res = get(e, propertyName, nullValue);
    return res == null ? getPropertyNullValue(propertyType) : res;
  }

  private static Object getPropertyNullValue(Class clazz) {
    if (Integer.class.equals(clazz)) {
      return 0;
    } else if (Long.class.equals(clazz)) {
      return (long) 0;
    } else if (Double.class.equals(clazz)) {
      return (double) 0;
    } else if (Float.class.equals(clazz)) {
      return (float) 0;
    } else if (Short.class.equals(clazz)) {
      return (short) 0;
    } else if (Byte.class.equals(clazz)) {
      return (byte) 0;
    } else if (Boolean.class.equals(clazz)) {
      return false;
    }

    return null;
  }

  public static Comparable set(@NotNull Entity e, @NotNull String propertyName, @Nullable Comparable propertyValue) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    Comparable oldPropertyValue = e.getProperty(propertyName);

    if (propertyValue == null && oldPropertyValue != null) {
      e.deleteProperty(propertyName);
    } else if (propertyValue != null && !propertyValue.equals(oldPropertyValue)) {
      e.setProperty(propertyName, propertyValue);
    }
    return propertyValue;
  }

  public static Comparable set(@NotNull Entity e, @NotNull String propertyName, @Nullable Comparable propertyValue, Class clazz) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    Comparable oldPropertyValue = e.getProperty(propertyName);

    if (propertyValue == null && oldPropertyValue != null) {
      e.deleteProperty(propertyName);
    } else if (propertyValue != null) {
      // strict casting
      if (Integer.class.equals(clazz)) {
        final Integer intValue = ((Number) propertyValue).intValue();
        if (!intValue.equals(oldPropertyValue)) {
          e.setProperty(propertyName, intValue);
        }
      } else if (Long.class.equals(clazz)) {
        final Long longValue = ((Number) propertyValue).longValue();
        if (!longValue.equals(oldPropertyValue)) {
          e.setProperty(propertyName, longValue);
        }
      } else if (Double.class.equals(clazz)) {
        final Double doubleValue = ((Number) propertyValue).doubleValue();
        if (!doubleValue.equals(oldPropertyValue)) {
          e.setProperty(propertyName, doubleValue);
        }
      } else if (Float.class.equals(clazz)) {
        final Float floatValue = ((Number) propertyValue).floatValue();
        if (!floatValue.equals(oldPropertyValue)) {
          e.setProperty(propertyName, floatValue);
        }
      } else if (Short.class.equals(clazz)) {
        final Short shortValue = ((Number) propertyValue).shortValue();
        if (!shortValue.equals(oldPropertyValue)) {
          e.setProperty(propertyName, shortValue);
        }
      } else if (Byte.class.equals(clazz)) {
        final Byte byteValue = ((Number) propertyValue).byteValue();
        if (!byteValue.equals(oldPropertyValue)) {
          e.setProperty(propertyName, byteValue);
        }
      } else {
        // boolean, string and date
        if (!propertyValue.equals(oldPropertyValue)) {
          e.setProperty(propertyName, propertyValue);
        }
      }
    }
    return propertyValue;
  }

  public static void setHashed(@NotNull Entity e, @NotNull String propertyName, String value) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    String oldPropertyValue = (String) e.getProperty(propertyName);

    if (value == null && oldPropertyValue != null) {
      e.deleteProperty(propertyName);
    } else if (value != null) {
      value = MessageDigestUtil.sha256(value);
      if (oldPropertyValue == null || !value.equals(oldPropertyValue)) {
        e.setProperty(propertyName, value);
      }
    }
  }

  public static InputStream getBlob(@NotNull Entity e, @NotNull String blobName) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    if (e == null) {
      return null;
    }

    return e.getBlob(blobName);
  }

  public static String getBlobAsString(@NotNull Entity e, @NotNull String blobName) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    if (e == null) {
      return null;
    }

    return e.getBlobString(blobName);
  }

  public static Comparable setBlob(@NotNull Entity e, @NotNull String blobName, @Nullable String blobString) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    String oldPropertyValue = e.getBlobString(blobName);

    if (blobString == null && oldPropertyValue != null) {
      ((TransientEntity) e).deleteBlobString(blobName);
    } else if (blobString != null && !blobString.equals(oldPropertyValue)) {
      e.setBlobString(blobName, blobString);
    }
    return blobString;
  }

  public static Comparable setBlobWithFixedNewlines(@NotNull Entity e, @NotNull String blobName, @Nullable String blobString) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    String oldPropertyValue = e.getBlobString(blobName);

    if (blobString == null && oldPropertyValue != null) {
      ((TransientEntity) e).deleteBlobString(blobName);
    } else if (blobString != null) {
      final String fixed = (blobString.indexOf('\r') >= 0) ? blobString.replace("\r", "") : blobString;
      if (!fixed.equals(oldPropertyValue)) {
        e.setBlobString(blobName, fixed);
        return fixed;
      }                                                                                               
    }
    return blobString;
  }

  public static void setBlob(@NotNull Entity e, @NotNull String blobName, @Nullable InputStream blob) {
    e = TransientStoreUtil.reattach((TransientEntity) e);
    if (blob == null) {
      e.deleteBlob(blobName);
    } else {
      e.setBlob(blobName, blob);
    }
  }

  public static void setBlob(@NotNull Entity e, @NotNull String blobName, @Nullable File file) {
    e = TransientStoreUtil.reattach((TransientEntity) e);
    if (file == null) {
      e.deleteBlob(blobName);
    } else {
      e.setBlob(blobName, file);
    }
  }

  public static long getSequenceValue(@NotNull final TransientStoreSession session,
                                      @NotNull final Entity instance,
                                      @NotNull final String sequenceName) {
    try {
      return session.getSequence(instance.getId().toString() + sequenceName).get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void setSequenceValue(@NotNull final TransientStoreSession session,
                                      @NotNull final Entity instance,
                                      @NotNull final String sequenceName,
                                      final long value) {
    try {
      session.getSequence(instance.getId().toString() + sequenceName).set(value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static long incSequenceValue(@NotNull final TransientStoreSession session,
                                      @NotNull final Entity instance,
                                      @NotNull final String sequenceName) {
    try {
      return session.getSequence(instance.getId().toString() + sequenceName).increment();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static Comparable nextGreater(@NotNull final Comparable value, @NotNull final Class clazz) {
    if (Integer.class.equals(clazz)) {
      return ((Integer) value) + 1;
    } else if (Long.class.equals(clazz)) {
      return ((Long) value) + 1;
    } else if (Float.class.equals(clazz)) {
      float result;
      float addend = FLOAT_PRECISION;
      do {
        result = (Float) value + addend;
        addend *= 2;
      } while (value.equals(result));
      return result;
    } else if (Double.class.equals(clazz)) {
      double result;
      double addend = DOUBLE_PRECISION;
      do {
        result = (Double) value + addend;
        addend *= 2;
      } while (value.equals(result));
      return result;
    } else if (Short.class.equals(clazz)) {
      return ((Short) value) + 1;
    } else if (Byte.class.equals(clazz)) {
      return ((Byte) value) + 1;
    } else if (Boolean.class.equals(clazz)) {
      return Boolean.TRUE;
    }
    return null;
  }

  public static Comparable previousLess(@NotNull final Comparable value, @NotNull final Class clazz) {
    if (Integer.class.equals(clazz)) {
      return ((Integer) value) - 1;
    } else if (Long.class.equals(clazz)) {
      return ((Long) value) - 1;
    } else if (Float.class.equals(clazz)) {
      float result;
      float subtrahend = FLOAT_PRECISION;
      do {
        result = (Float) value - subtrahend;
        subtrahend *= 2;
      } while (value.equals(result));
      return result;
    } else if (Double.class.equals(clazz)) {
      double result;
      double subtrahend = DOUBLE_PRECISION;
      do {
        result = (Double) value - subtrahend;
        subtrahend *= 2;
      } while (value.equals(result));
      return result;
    } else if (Short.class.equals(clazz)) {
      return ((Short) value) - 1;
    } else if (Byte.class.equals(clazz)) {
      return ((Byte) value) - 1;
    } else if (Boolean.class.equals(clazz)) {
      return Boolean.FALSE;
    }
    return null;
  }

  public static Comparable positiveInfinity(@NotNull final Class clazz) {
    if (Integer.class.equals(clazz)) {
      return Integer.MAX_VALUE;
    } else if (Long.class.equals(clazz)) {
      return Long.MAX_VALUE;
    } else if (Float.class.equals(clazz)) {
      return Float.MAX_VALUE;
    } else if (Double.class.equals(clazz)) {
      return Double.MAX_VALUE;
    } else if (Short.class.equals(clazz)) {
      return Short.MAX_VALUE;
    } else if (Byte.class.equals(clazz)) {
      return Byte.MAX_VALUE;
    }
    if (Boolean.class.equals(clazz)) {
      return Boolean.TRUE;
    }
    return null;
  }

  public static Comparable negativeInfinity(@NotNull final Class clazz) {
    if (Integer.class.equals(clazz)) {
      return Integer.MIN_VALUE;
    } else if (Long.class.equals(clazz)) {
      return Long.MIN_VALUE;
    } else if (Float.class.equals(clazz)) {
      return -Float.MAX_VALUE;
    } else if (Double.class.equals(clazz)) {
      return -Double.MAX_VALUE;
    } else if (Short.class.equals(clazz)) {
      return Short.MIN_VALUE;
    } else if (Byte.class.equals(clazz)) {
      return Byte.MIN_VALUE;
    }
    if (Boolean.class.equals(clazz)) {
      return Boolean.FALSE;
    }
    return null;
  }
}
