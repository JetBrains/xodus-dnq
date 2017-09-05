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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.util.fromBitsArray
import kotlinx.dnq.util.toBitsArray
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CachedPropertiesTest(val size: Int, val int: Int, val array: List<Boolean>) {

    @Test
    fun `toBitsArray should convert int to boolean array of expected size`() {
        assertThat(int.toBitsArray(size).size)
                .isEqualTo(size)
    }

    @Test
    fun `toBitsArray should convert bits to flag array correctly`() {
        val actual = int.toBitsArray(size)
        assertThat(actual)
                .isEqualTo(array.toBooleanArray())
    }

    @Test
    fun `fromBitsArray should convert flag array to bits correctly`() {
        assertThat(array.toBooleanArray().fromBitsArray())
                .isEqualTo(int.and(1.shl(size) - 1))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "size={0}, int={1}, array={2}")
        fun data() = listOf<Array<Any>>(
                arrayOf(0, 0, listOf<Boolean>()),
                arrayOf(0, 1, listOf<Boolean>()),
                arrayOf(0, 42, listOf<Boolean>()),
                arrayOf(1, 0, listOf(false)),
                arrayOf(1, 1, listOf(true)),
                arrayOf(2, 0, listOf(false, false)),
                arrayOf(2, 1, listOf(false, true)),
                arrayOf(2, 2, listOf(true, false)),
                arrayOf(2, 3, listOf(true, true)),
                arrayOf(2, 4, listOf(false, false))
        )
    }
}