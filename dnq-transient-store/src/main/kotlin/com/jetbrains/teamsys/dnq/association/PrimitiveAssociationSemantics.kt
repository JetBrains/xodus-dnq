/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package com.jetbrains.teamsys.dnq.association

import com.jetbrains.teamsys.dnq.database.reattachTransient
import jetbrains.exodus.core.crypto.MessageDigestUtil
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import java.io.File
import java.io.InputStream

object PrimitiveAssociationSemantics {

    private val FLOAT_PRECISION = 0.0001f
    private val DOUBLE_PRECISION = 0.0000001

    /**
     * Simple property getter.
     * Supports nullable objects - returns "null value" if input entity is null.
     */
    @JvmStatic
    fun get(e: Entity?, propertyName: String, nullValue: Any?): Any? {
        val txnEntity = e?.reattachTransient()

        return if (txnEntity != null) {
            txnEntity.getProperty(propertyName)
        } else {
            nullValue
        }
    }

    @JvmStatic
    fun <T> getOldValue(e: TransientEntity?, propertyName: String, propertyType: Class<T>?, nullValue: Any?): T? {
        val value = getOldValue(e, propertyName, nullValue)
        @Suppress("UNCHECKED_CAST")
        return (value ?: getPropertyNullValue(propertyType)) as T?
    }

    /**
     * Property old value getter, similar as #get.
     * Supports nullable objects - returns "null value" if input entity is null
     */
    @JvmStatic
    fun getOldValue(e: TransientEntity?, propertyName: String, nullValue: Any?): Any? {
        return if (e == null) nullValue else e.getPropertyOldValue(propertyName)
    }

    /**
     * Simple property getter.
     * Supports nullable objects - returns "null value" if input entity is null
     */
    @JvmStatic
    fun <T> get(e: Entity?, propertyName: String, propertyType: Class<T>?, nullValue: Any?): T? {
        val value = get(e, propertyName, nullValue)
        @Suppress("UNCHECKED_CAST")
        return (value ?: getPropertyNullValue(propertyType)) as T?
    }

    @JvmStatic
    private fun getPropertyNullValue(clazz: Class<*>?): Any? {
        return when (clazz) {
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType -> 0
            Long::class.javaPrimitiveType,
            Long::class.javaObjectType -> 0.toLong()
            Double::class.javaPrimitiveType,
            Double::class.javaObjectType -> 0.toDouble()
            Float::class.javaPrimitiveType,
            Float::class.javaObjectType -> 0.toFloat()
            Short::class.javaPrimitiveType,
            Short::class.javaObjectType -> 0.toShort()
            Byte::class.javaPrimitiveType,
            Byte::class.javaObjectType -> 0.toByte()
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaObjectType -> false
            else -> null
        }
    }

    @JvmStatic
    fun set(e: Entity, propertyName: String, propertyValue: Comparable<*>?): Comparable<*>? {
        val txnEntity = e.reattachTransient()
        if (propertyValue == null) {
            txnEntity.deleteProperty(propertyName)
        } else {
            txnEntity.setProperty(propertyName, propertyValue)
        }
        return propertyValue
    }

    @JvmStatic
    fun set(e: Entity, propertyName: String, propertyValue: Comparable<*>?, clazz: Class<*>?): Comparable<*>? {
        val txnEntity = e.reattachTransient()
        if (propertyValue == null) {
            txnEntity.deleteProperty(propertyName)
        } else {
            // strict casting
            when (clazz) {
                Int::class.javaPrimitiveType,
                Int::class.javaObjectType ->
                    txnEntity.setProperty(propertyName, (propertyValue as Number).toInt())
                Long::class.javaPrimitiveType,
                Long::class.javaObjectType ->
                    txnEntity.setProperty(propertyName, (propertyValue as Number).toLong())
                Double::class.javaPrimitiveType,
                Double::class.javaObjectType ->
                    txnEntity.setProperty(propertyName, (propertyValue as Number).toDouble())
                Float::class.javaPrimitiveType,
                Float::class.javaObjectType ->
                    txnEntity.setProperty(propertyName, (propertyValue as Number).toFloat())
                Short::class.javaPrimitiveType,
                Short::class.javaObjectType ->
                    txnEntity.setProperty(propertyName, (propertyValue as Number).toShort())
                Byte::class.javaPrimitiveType,
                Byte::class.javaObjectType ->
                    txnEntity.setProperty(propertyName, (propertyValue as Number).toByte())
                else -> txnEntity.setProperty(propertyName, propertyValue) // boolean, string and date
            }
        }
        return propertyValue
    }

    @JvmStatic
    fun setHashed(e: Entity, propertyName: String, value: String?) {
        val txnEntity = e.reattachTransient()
        if (value == null) {
            txnEntity.deleteProperty(propertyName)
        } else {
            val hashedValue = MessageDigestUtil.sha256(value)
            txnEntity.setProperty(propertyName, hashedValue)
        }
    }

    @JvmStatic
    fun getBlob(e: Entity?, blobName: String): InputStream? {
        return e?.reattachTransient()?.getBlob(blobName)
    }

    @JvmStatic
    fun getBlobSize(e: Entity?, blobName: String): Long {
        return e?.reattachTransient()?.getBlobSize(blobName) ?: -1
    }

    @JvmStatic
    fun getBlobAsString(e: Entity?, blobName: String): String? {
        return e?.reattachTransient()?.getBlobString(blobName)
    }

    @JvmStatic
    fun setBlob(e: Entity, blobName: String, blobString: String?): Comparable<*>? {
        val txnEntity = e.reattachTransient()
        if (blobString == null) {
            txnEntity.deleteBlob(blobName)
        } else {
            txnEntity.setBlobString(blobName, blobString)
        }
        return blobString
    }

    @JvmStatic
    fun setBlobWithFixedNewlines(e: Entity, blobName: String, blobString: String?): Comparable<*>? {
        val txnEntity = e.reattachTransient()
        return if (blobString == null) {
            txnEntity.deleteBlob(blobName)
            blobString
        } else {
            val fixed = if (blobString.indexOf('\r') >= 0) blobString.replace("\r", "") else blobString
            txnEntity.setBlobString(blobName, fixed)
            fixed
        }
    }

    @JvmStatic
    fun setBlob(e: Entity, blobName: String, blob: InputStream?) {
        val txnEntity = e.reattachTransient()
        if (blob == null) {
            txnEntity.deleteBlob(blobName)
        } else {
            txnEntity.setBlob(blobName, blob)
        }
    }

    @JvmStatic
    fun setBlob(e: Entity, blobName: String, file: File?) {
        val txnEntity = e.reattachTransient()
        if (file == null) {
            txnEntity.deleteBlob(blobName)
        } else {
            txnEntity.setBlob(blobName, file)
        }
    }

    @JvmStatic
    fun getSequenceValue(session: TransientStoreSession,
                         instance: Entity,
                         sequenceName: String): Long {
        return session.getSequence("${instance.id}$sequenceName").get()
    }

    @JvmStatic
    fun setSequenceValue(session: TransientStoreSession,
                         instance: Entity,
                         sequenceName: String,
                         value: Long) {
        session.getSequence("${instance.id}$sequenceName").set(value)
    }

    @JvmStatic
    fun incSequenceValue(session: TransientStoreSession,
                         instance: Entity,
                         sequenceName: String): Long {
        return session.getSequence("${instance.id}$sequenceName").increment()
    }

    @JvmStatic
    fun nextGreater(value: Comparable<*>, clazz: Class<*>): Comparable<*>? {
        return when (clazz) {
            Byte::class.javaPrimitiveType,
            Byte::class.javaObjectType ->
                value as Byte + 1
            Short::class.javaPrimitiveType,
            Short::class.javaObjectType ->
                value as Short + 1
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType ->
                value as Int + 1
            Long::class.javaPrimitiveType,
            Long::class.javaObjectType ->
                value as Long + 1
            Float::class.javaPrimitiveType,
            Float::class.javaObjectType -> {
                var result: Float
                var addend = FLOAT_PRECISION
                do {
                    result = value as Float + addend
                    addend *= 2f
                } while (value == result)
                result
            }
            Double::class.javaPrimitiveType,
            Double::class.javaObjectType -> {
                var result: Double
                var addend = DOUBLE_PRECISION
                do {
                    result = value as Double + addend
                    addend *= 2.0
                } while (value == result)
                result
            }
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaObjectType ->
                java.lang.Boolean.TRUE
            else -> null
        }
    }

    @JvmStatic
    fun previousLess(value: Comparable<*>, clazz: Class<*>): Comparable<*>? {
        return when (clazz) {
            Byte::class.javaPrimitiveType,
            Byte::class.javaObjectType ->
                value as Byte - 1
            Short::class.javaPrimitiveType,
            Short::class.javaObjectType ->
                value as Short - 1
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType ->
                value as Int - 1
            Long::class.javaPrimitiveType,
            Long::class.javaObjectType ->
                value as Long - 1
            Float::class.javaPrimitiveType,
            Float::class.javaObjectType -> {
                var result: Float
                var subtrahend = FLOAT_PRECISION
                do {
                    result = value as Float - subtrahend
                    subtrahend *= 2f
                } while (value == result)
                result
            }
            Double::class.javaPrimitiveType,
            Double::class.javaObjectType -> {
                var result: Double
                var subtrahend = DOUBLE_PRECISION
                do {
                    result = value as Double - subtrahend
                    subtrahend *= 2.0
                } while (value == result)
                result
            }
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaObjectType ->
                false
            else -> null
        }
    }

    @JvmStatic
    fun positiveInfinity(clazz: Class<*>): Comparable<*>? {
        return when (clazz) {
            Byte::class.javaPrimitiveType,
            Byte::class.javaObjectType ->
                java.lang.Byte.MAX_VALUE
            Short::class.javaPrimitiveType,
            Short::class.javaObjectType ->
                java.lang.Short.MAX_VALUE
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType ->
                Integer.MAX_VALUE
            Long::class.javaPrimitiveType,
            Long::class.javaObjectType ->
                java.lang.Long.MAX_VALUE
            Float::class.javaPrimitiveType,
            Float::class.javaObjectType ->
                java.lang.Float.MAX_VALUE
            Double::class.javaPrimitiveType,
            Double::class.javaObjectType ->
                java.lang.Double.MAX_VALUE
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaObjectType ->
                true
            else -> null
        }
    }

    @JvmStatic
    fun negativeInfinity(clazz: Class<*>): Comparable<*>? {
        return when (clazz) {
            Byte::class.javaPrimitiveType,
            Byte::class.javaObjectType ->
                java.lang.Byte.MIN_VALUE
            Short::class.javaPrimitiveType,
            Short::class.javaObjectType ->
                java.lang.Short.MIN_VALUE
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType ->
                Integer.MIN_VALUE
            Long::class.javaPrimitiveType,
            Long::class.javaObjectType ->
                java.lang.Long.MIN_VALUE
            Float::class.javaPrimitiveType,
            Float::class.javaObjectType ->
                -java.lang.Float.MAX_VALUE
            Double::class.javaPrimitiveType,
            Double::class.javaObjectType ->
                -java.lang.Double.MAX_VALUE
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaObjectType ->
                java.lang.Boolean.FALSE
            else -> null
        }
    }
}
