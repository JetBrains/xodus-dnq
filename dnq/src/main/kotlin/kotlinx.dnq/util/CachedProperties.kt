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