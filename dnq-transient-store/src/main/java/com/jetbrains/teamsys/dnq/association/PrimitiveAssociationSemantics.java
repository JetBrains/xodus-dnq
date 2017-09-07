/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.teamsys.dnq.association;

import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import jetbrains.exodus.core.crypto.MessageDigestUtil;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.TransientStoreSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;

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
    public static<T> T getOldValue(@Nullable TransientEntity e, @NotNull String propertyName, @NotNull Class<T> propertyType, @Nullable Object nullValue) {
        Object res = getOldValue(e, propertyName, nullValue);
        return (T) (res == null ? getPropertyNullValue(propertyType) : res);
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
    public static Object getOldValue(@Nullable TransientEntity e, @NotNull String propertyName, @Nullable Object nullValue) {
        if (e == null) return nullValue;
        return e.getPropertyOldValue(propertyName);
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
    public static<T> T get(@Nullable Entity e, @NotNull String propertyName, @NotNull Class<T> propertyType, @Nullable Object nullValue) {
        final Object res = get(e, propertyName, nullValue);
        return (T) (res == null ? getPropertyNullValue(propertyType) : res);
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

        if (propertyValue == null) {
            e.deleteProperty(propertyName);
        } else {
            e.setProperty(propertyName, propertyValue);
        }
        return propertyValue;
    }

    public static Comparable set(@NotNull Entity e, @NotNull String propertyName, @Nullable Comparable propertyValue, Class clazz) {
        e = TransientStoreUtil.reattach((TransientEntity) e);

        if (propertyValue == null) {
            e.deleteProperty(propertyName);
        } else {
            // strict casting
            if (Integer.class.equals(clazz)) {
                final Integer intValue = ((Number) propertyValue).intValue();
                e.setProperty(propertyName, intValue);
            } else if (Long.class.equals(clazz)) {
                final Long longValue = ((Number) propertyValue).longValue();
                e.setProperty(propertyName, longValue);
            } else if (Double.class.equals(clazz)) {
                final Double doubleValue = ((Number) propertyValue).doubleValue();
                e.setProperty(propertyName, doubleValue);
            } else if (Float.class.equals(clazz)) {
                final Float floatValue = ((Number) propertyValue).floatValue();
                e.setProperty(propertyName, floatValue);
            } else if (Short.class.equals(clazz)) {
                final Short shortValue = ((Number) propertyValue).shortValue();
                e.setProperty(propertyName, shortValue);
            } else if (Byte.class.equals(clazz)) {
                final Byte byteValue = ((Number) propertyValue).byteValue();
                e.setProperty(propertyName, byteValue);
            } else {
                // boolean, string and date
                e.setProperty(propertyName, propertyValue);
            }
        }
        return propertyValue;
    }

    public static void setHashed(@NotNull Entity e, @NotNull String propertyName, String value) {
        e = TransientStoreUtil.reattach((TransientEntity) e);

        if (value == null) {
            e.deleteProperty(propertyName);
        } else if (value != null) {
            value = MessageDigestUtil.sha256(value);
            e.setProperty(propertyName, value);
        }
    }

    public static InputStream getBlob(@NotNull Entity e, @NotNull String blobName) {
        e = TransientStoreUtil.reattach((TransientEntity) e);

        if (e == null) {
            return null;
        }

        return e.getBlob(blobName);
    }

    public static long getBlobSize(@NotNull Entity e, @NotNull String blobName) {
        e = TransientStoreUtil.reattach((TransientEntity) e);

        if (e == null) {
            return -1;
        }

        return e.getBlobSize(blobName);
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

        if (blobString == null) {
            ((TransientEntity) e).deleteBlob(blobName);
        } else {
            e.setBlobString(blobName, blobString);
        }
        return blobString;
    }

    public static Comparable setBlobWithFixedNewlines(@NotNull Entity e, @NotNull String blobName, @Nullable String blobString) {
        e = TransientStoreUtil.reattach((TransientEntity) e);

        if (blobString == null) {
            ((TransientEntity) e).deleteBlob(blobName);
        } else {
            final String fixed = (blobString.indexOf('\r') >= 0) ? blobString.replace("\r", "") : blobString;
            e.setBlobString(blobName, fixed);
            return fixed;
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
