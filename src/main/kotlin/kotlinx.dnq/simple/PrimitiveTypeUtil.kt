package kotlinx.dnq.simple

import kotlin.reflect.KClass


private val FLOAT_PRECISION = 0.0001f
private val DOUBLE_PRECISION = 0.0000001

fun <V : Comparable<V>> KClass<V>.next(value: V): Comparable<*>? = when (this) {
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
    else -> null
}

fun <V : Comparable<V>> KClass<V>.prev(value: V): Comparable<*>? = when (this) {
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
    else -> null
}

fun <V : Comparable<V>> KClass<V>.maxValue(): Comparable<*>? = when (this) {
    Boolean::class -> true
    Byte::class -> Byte.MAX_VALUE
    Short::class -> Short.MAX_VALUE
    Int::class -> Integer.MAX_VALUE
    Long::class -> Long.MAX_VALUE
    Float::class -> Float.MAX_VALUE
    Double::class -> Double.MAX_VALUE
    else -> null
}

fun <V : Comparable<V>> KClass<V>.minValue(): Comparable<*>? = when (this) {
    Boolean::class -> false
    Byte::class -> Byte.MIN_VALUE
    Short::class -> Short.MIN_VALUE
    Int::class -> Integer.MIN_VALUE
    Long::class -> Long.MIN_VALUE
    Float::class -> -Float.MAX_VALUE
    Double::class -> -Double.MAX_VALUE
    else -> null
}
