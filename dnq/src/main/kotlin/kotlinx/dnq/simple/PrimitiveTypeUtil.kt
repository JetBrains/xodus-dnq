/**
 * Copyright 2006 - 2019 JetBrains s.r.o.
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
package kotlinx.dnq.simple

import kotlinx.dnq.simple.custom.type.XdCustomTypeBindingRegistry
import kotlin.reflect.KClass


private val FLOAT_PRECISION = 0.0001f
private val DOUBLE_PRECISION = 0.0000001

fun <V : Comparable<V>> KClass<V>.next(value: V) = nextRaw(value) as Comparable

fun <V : Comparable<V>> KClass<V>.nextRaw(value: V): Comparable<*>? = when (this) {
    Boolean::class -> true
    Byte::class -> ((value as Number).toByte() + 1).toByte()
    Short::class -> ((value as Number).toShort() + 1).toShort()
    Int::class -> (value as Number).toInt() + 1
    Long::class -> (value as Number).toLong() + 1
    Float::class -> {
        val fValue = (value as Number).toFloat()
        generateSequence(FLOAT_PRECISION) { it * 2 }
                .map { fValue + it }
                .first { it != fValue }
    }
    Double::class -> {
        val dValue = (value as Number).toDouble()
        generateSequence(DOUBLE_PRECISION) { it * 2 }
                .map { dValue + it }
                .first { it != dValue }
    }
    else -> XdCustomTypeBindingRegistry[this.java]?.next(value)
}

fun <V : Comparable<V>> KClass<V>.prev(value: V) = prevRaw(value) as Comparable

fun <V : Comparable<V>> KClass<V>.prevRaw(value: V): Comparable<*>? = when (this) {
    Boolean::class -> false
    Byte::class -> ((value as Number).toByte() - 1).toByte()
    Short::class -> ((value as Number).toShort() - 1).toShort()
    Int::class -> (value as Number).toInt() - 1
    Long::class -> (value as Number).toLong() - 1
    Float::class -> {
        val fValue = (value as Number).toFloat()
        generateSequence(FLOAT_PRECISION) { it * 2 }
                .map { fValue - it }
                .first { it != fValue }
    }
    Double::class -> {
        val dValue = (value as Number).toDouble()
        generateSequence(DOUBLE_PRECISION) { it * 2 }
                .map { dValue - it }
                .first { it != dValue }
    }
    else -> XdCustomTypeBindingRegistry[this.java]?.prev(value)
}

fun <V : Comparable<V>> KClass<V>.maxValue() = maxValueRaw() as Comparable

fun <V : Comparable<V>> KClass<V>.maxValueRaw(): Comparable<*>? = when (this) {
    Boolean::class -> true
    Byte::class -> Byte.MAX_VALUE
    Short::class -> Short.MAX_VALUE
    Int::class -> Integer.MAX_VALUE
    Long::class -> Long.MAX_VALUE
    Float::class -> Float.MAX_VALUE
    Double::class -> Double.MAX_VALUE
    else -> XdCustomTypeBindingRegistry[this.java]?.max()
}

fun <V : Comparable<V>> KClass<V>.minValue() = minValueRaw() as Comparable

fun <V : Comparable<V>> KClass<V>.minValueRaw(): Comparable<*>? = when (this) {
    Boolean::class -> false
    Byte::class -> Byte.MIN_VALUE
    Short::class -> Short.MIN_VALUE
    Int::class -> Integer.MIN_VALUE
    Long::class -> Long.MIN_VALUE
    Float::class -> -Float.MAX_VALUE
    Double::class -> -Double.MAX_VALUE
    else -> XdCustomTypeBindingRegistry[this.java]?.min()
}
