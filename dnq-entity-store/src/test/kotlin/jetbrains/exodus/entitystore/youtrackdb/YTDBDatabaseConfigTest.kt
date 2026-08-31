/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore.youtrackdb

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration
import org.junit.Assert
import org.junit.Test

class YTDBDatabaseConfigTest {

    private fun params(tweak: YTDBDatabaseParams.Builder.() -> Unit = {}) = YTDBDatabaseParams.builder()
        .withDatabasePath("aa")
        .withDatabaseName("aa")
        .apply(tweak)
        .build()

    private val fsyncKey = GlobalConfiguration.STORAGE_CALL_FSYNC.key

    @Test
    fun `non-transactional index fallback is enabled by default`() {
        Assert.assertTrue(params().allowNonTransactionalIndexFallback)
    }

    @Test
    fun `non-transactional index fallback can be disabled`() {
        val params = params { withAllowNonTransactionalIndexFallback(false) }

        Assert.assertFalse(params.allowNonTransactionalIndexFallback)
    }

    @Test
    fun `batched sequence acquisition is disabled by default`() {
        Assert.assertFalse(params().useBatchedSequenceAcquisition)
    }

    @Test
    fun `batched sequence acquisition can be explicitly enabled`() {
        val params = params { withBatchedSequenceAcquisition(true) }

        Assert.assertTrue(params.useBatchedSequenceAcquisition)
    }

    @Test
    fun `encryption key calculated from hex`() {
        val keyHex = "546e6f624b737371796f41586e7269304c744f42663252613630586631374a67"

        val params = YTDBDatabaseParams.builder()
            .withDatabasePath("aa")
            .withDatabaseName("aa")
            .withHexEncryptionKey(keyHex, 0)
            .build()

        Assert.assertEquals("VG5vYktzc3F5b0FYbnJpMAAAAAAAAAAA", params.encryptionKey)
    }

    @Test
    fun `encryption key is trunked to 32 from bigger one`() {
        val key1 = Array(60) { "aa" }.joinToString(separator = "")

        val params = YTDBDatabaseParams.builder()
            .withDatabasePath("aa")
            .withDatabaseName("aa")
            .withHexEncryptionKey(key1, 0)
            .build()

        Assert.assertEquals(32, params.encryptionKey?.length)
    }

    @Test
    fun `encryption key is not trunked if key is smaller than 32`() {
        val key1 = "aabbccddaabbccdd"

        val params = YTDBDatabaseParams.builder()
            .withDatabasePath("aa")
            .withDatabaseName("aa")
            .withHexEncryptionKey(key1, 0)
            .build()

        Assert.assertEquals(24, params.encryptionKey?.length)
    }

    @Test
    fun `fsync is left untouched by default`() {
        val params = params()

        Assert.assertNull(params.callFsync)
        // Nothing must be written into the context configuration, otherwise this database would
        // shadow the process-wide GlobalConfiguration value.
        Assert.assertFalse(params.youTrackDBConfig.configuration.contextKeys.contains(fsyncKey))
    }

    @Test
    fun `fsync can be disabled`() {
        val params = params { withCallFsync(false) }

        Assert.assertEquals(false, params.callFsync)
        val configuration = params.youTrackDBConfig.configuration
        Assert.assertTrue(configuration.contextKeys.contains(fsyncKey))
        Assert.assertFalse(configuration.getValueAsBoolean(GlobalConfiguration.STORAGE_CALL_FSYNC))
    }

    @Test
    fun `fsync can be explicitly enabled`() {
        val params = params { withCallFsync(true) }

        Assert.assertEquals(true, params.callFsync)
        val configuration = params.youTrackDBConfig.configuration
        Assert.assertTrue(configuration.contextKeys.contains(fsyncKey))
        Assert.assertTrue(configuration.getValueAsBoolean(GlobalConfiguration.STORAGE_CALL_FSYNC))
    }

    @Test
    fun `an explicit config builder still overrides the fsync flag`() {
        val params = params {
            withCallFsync(false)
            withConfigBuilder {
                addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_CALL_FSYNC, true)
            }
        }

        Assert.assertTrue(
            params.youTrackDBConfig.configuration.getValueAsBoolean(GlobalConfiguration.STORAGE_CALL_FSYNC)
        )
    }
}
