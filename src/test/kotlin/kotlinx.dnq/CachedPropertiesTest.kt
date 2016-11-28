package kotlinx.dnq

import kotlinx.dnq.util.fromBitsArray
import kotlinx.dnq.util.toBitsArray
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class CachedPropertiesTest(val size: Int, val int: Int, val array: List<Boolean>) {


    @Test
    fun `toBitsArray should convert int to boolean array of expected size`() {
        assertEquals(size, int.toBitsArray(size).size)
    }

    @Test
    fun `toBitsArray should convert bits to flag array correctly`() {
        val actual = int.toBitsArray(size)
        println("Exp: $array")
        println("Act: ${actual.toList()}")
        Assert.assertArrayEquals(array.toBooleanArray(), actual)
    }

    @Test
    fun `fromBitsArray should convert flag array to bits correctly`() {
        assertEquals(int.and(1.shl(size) - 1), array.toBooleanArray().fromBitsArray())
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