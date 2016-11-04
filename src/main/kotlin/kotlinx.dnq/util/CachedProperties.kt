package kotlinx.dnq.util

internal class CachedProperties<out R>(val size: Int, create: (BooleanArray) -> R) {
    val cache = (1..1.shl(size)).map {
        create((it - 1).toBitsArray(size))
    }

    operator fun get(vararg flags: Boolean): R {
        return cache[flags.fromBitsArray()]
    }
}

internal fun Int.toBitsArray(size: Int): BooleanArray {
    return BooleanArray(size) { index ->
        this@toBitsArray.shr(size - index - 1).and(1) != 0
    }
}

internal fun BooleanArray.fromBitsArray(): Int {
    return fold(0) { result, flag ->
        result.shl(1).or(if (flag) 1 else 0)
    }
}