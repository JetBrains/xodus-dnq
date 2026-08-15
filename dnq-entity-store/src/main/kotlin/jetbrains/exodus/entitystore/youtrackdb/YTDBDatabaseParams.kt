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

import com.jetbrains.youtrackdb.api.DatabaseType
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfigBuilder
import java.util.*
import kotlin.math.min

class YTDBDatabaseParams private constructor(
    val databasePath: String,
    val databaseName: String,
    val databaseType: DatabaseType,
    val appUser: YTDBUser,
    val additionalUsers: List<YTDBUser> = emptyList(),
    val encryptionKey: String?,
    val closeDatabaseInDbProvider: Boolean,
    val closeAfterDelayTimeout: Int,
    val serverParams: YTDBServerParams? = null,
    val configBuilder: YouTrackDBConfigBuilder.() -> Unit = {},
    /**
     * Dual-mode index creation (XD-1283).
     *
     * **The default is `true`**: all index definitions of a schema pass are created inside ONE
     * transaction, so the index pass is atomic (and much faster). When `false`, indices are
     * created on YTDB's legacy non-transactional path (createIndex + fillIndex over committed
     * rows).
     *
     * **Transactional index creation requires EMPTY classes** on the current YouTrackDB version
     * (upstream YTDB-1064): creating an index over a class that already holds rows - or whose
     * subtypes hold rows - is rejected at commit. The failure recurs on every RESTART
     * (`applySchema` is idempotent: the index stays absent, the class stays populated) - but an
     * in-process retry does not surface it either, because `ModelMetaDataImpl` memoizes the
     * model before invoking `onPrepared`, so a caught exception leaves a running model with the
     * index silently missing. **A database that already contains data must therefore pin this
     * flag to `false`** until YTDB-1064 is lifted. See [Builder.withTransactionalIndexCreation].
     *
     * The flag retires when YTDB-1064 is lifted.
     */
    val transactionalIndexCreation: Boolean = true,
    /**
     * Storage `fsync` switch, mapped onto YouTrackDB's `youtrackdb.storage.callFsync`
     * ([GlobalConfiguration.STORAGE_CALL_FSYNC]).
     *
     * `null` (the default) leaves the parameter untouched, so YouTrackDB's own default (`true`)
     * or whatever the process has set on the JVM-global [GlobalConfiguration] applies. A
     * non-`null` value is written into this database's context configuration, which **shadows**
     * the JVM-global value - that is why the default is `null` rather than `true`.
     *
     * `false` removes the durability barriers on the storage hot path: after a power loss the
     * database can lose recent data, and a truncated file registry can even leave it unopenable.
     * **Intended for unit tests and benchmarks only** - see [Builder.withCallFsync].
     *
     * Only disk-backed databases are affected ([DatabaseType.MEMORY] never syncs anything).
     * YouTrackDB logs a one-shot warning when it starts a storage with `fsync` disabled.
     */
    val callFsync: Boolean? = null
) {

    companion object {

        fun builder(): Builder {
            return Builder()
        }
    }

    val youTrackDBConfig: YouTrackDBConfig = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.AUTO_CLOSE_AFTER_DELAY, true)
        .addGlobalConfigurationParameter(GlobalConfiguration.AUTO_CLOSE_DELAY, closeAfterDelayTimeout)
        .addGlobalConfigurationParameter(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, true)
        .addGlobalConfigurationParameter(GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED, true)
        .apply {
            encryptionKey?.let { addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_ENCRYPTION_KEY, it) }
            callFsync?.let { addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_CALL_FSYNC, it) }
        }
        .apply(configBuilder)
        .build()

    @Suppress("unused")
    class Builder internal constructor() {

        private var databasePath: String = ""
        private var databaseName: String = ""
        private var databaseType: DatabaseType = DatabaseType.MEMORY
        private var appUser: YTDBUser = YTDBUser(name = "admin", password = "admin", role = "admin")
        private var additionalUsers: MutableList<YTDBUser> = mutableListOf()
        private var closeAfterDelayTimeout: Int = 10
        private var encryptionKey: String? = null
        private var closeDatabaseInDbProvider = true
        private var serverParams: YTDBServerParams? = null
        private var configBuilder: YouTrackDBConfigBuilder.() -> Unit = {}
        private var transactionalIndexCreation: Boolean = true
        private var callFsync: Boolean? = null

        fun withDatabasePath(databaseUrl: String) = apply {
            this.databasePath = databaseUrl
        }

        fun withDatabaseName(databaseName: String) = apply {
            this.databaseName = databaseName
        }

        fun withDatabaseType(databaseType: DatabaseType) = apply {
            this.databaseType = databaseType
        }

        fun withAppUser(name: String, password: String) = apply {
            this.appUser = YTDBUser(name = name, password = password, role = "admin")
        }

        fun withAdditionalUsers(users: List<YTDBUser>) = apply {
            this.additionalUsers = users.toMutableList()
        }

        fun addAdminUser(name: String, password: String) = apply {
            this.additionalUsers.add(YTDBUser(name, password, role = "admin"))
        }

        fun addWriterUser(name: String, password: String) = apply {
            this.additionalUsers.add(YTDBUser(name, password, role = "writer"))
        }

        fun addReaderUser(name: String, password: String) = apply {
            this.additionalUsers.add(YTDBUser(name, password, role = "reader"))
        }

        fun withCloseDatabaseInDbProvider(closeDatabaseInDbProvider: Boolean) = apply {
            this.closeDatabaseInDbProvider = closeDatabaseInDbProvider
        }

        fun withCloseAfterDelayTimeout(closeAfterDelayTimeout: Int) = apply {
            this.closeAfterDelayTimeout = closeAfterDelayTimeout
        }

        fun withEncryptionKey(encryptionKey: ByteArray) = apply {
            this.encryptionKey = Base64.getEncoder().encodeToString(encryptionKey)
        }

        fun withEncryptionKey(encryptionKey: String) = apply {
            this.encryptionKey = encryptionKey
        }

        fun withHexEncryptionKey(key: String, iv: Long) = apply {
            require(encryptionKey == null) { "Cipher is already initialized" }
            // Truncate the key to 16 bytes (32 hex symbols = 16 bytes) according to the YouTrackDB requirements
            val truncatedHex = key.substring(0, min(32, key.length))
            // 16 bytes hex + 8 bytes long iv = 24 bytes
            val bytes = HexFormat.of().parseHex(truncatedHex) + iv.toByteArray()
            withEncryptionKey(bytes)
        }

        fun withConfigBuilder(tweakConfig: YouTrackDBConfigBuilder.() -> Unit) = apply {
            this.configBuilder = tweakConfig
        }

        fun withServer(serverParams: YTDBServerParams) = apply {
            this.serverParams = serverParams
        }

        /**
         * Dual-mode index creation (XD-1283), see [YTDBDatabaseParams.transactionalIndexCreation].
         *
         * **The default is `true`** - one transaction for the whole index pass.
         *
         * **Transactional index creation requires EMPTY classes** on the current YouTrackDB
         * version (upstream YTDB-1064): an index over a class that already holds rows (or whose
         * subtypes hold rows) is rejected at commit. The failure recurs on every RESTART
         * (`applySchema` is idempotent: the index stays absent, the class stays populated) - but
         * an in-process retry does not surface it either, because `ModelMetaDataImpl` memoizes
         * the model before invoking `onPrepared`, so a caught exception leaves a running model
         * with the index silently missing.
         * **Pass `false` for a database that already contains data**, which keeps index creation
         * on YTDB's legacy non-transactional path (createIndex + fillIndex over committed rows);
         * that path supports populated classes.
         *
         * `false` is required in particular for:
         * - a schema upgrade that adds an index (e.g. one new indexed simple property) to a class
         *   that already holds data;
         * - the application's first `prepare()` after a Xodus -> YouTrackDB migration - the
         *   migrator creates no indices, so every class is populated by the time indices are
         *   built (see `XodusToOrientDataMigratorLauncher`).
         *
         * The flag retires when YTDB-1064 is lifted.
         */
        fun withTransactionalIndexCreation(transactionalIndexCreation: Boolean) = apply {
            this.transactionalIndexCreation = transactionalIndexCreation
        }

        /**
         * Storage `fsync` switch, see [YTDBDatabaseParams.callFsync].
         *
         * Pass `false` to turn YouTrackDB's `fsync` calls off
         * (`youtrackdb.storage.callFsync`), which removes the durability barriers on the storage
         * hot path and makes disk-backed databases considerably cheaper to create and write.
         *
         * **Use `false` in unit tests and benchmarks only.** With `fsync` off, a power loss or a
         * JVM crash can lose recent data, and a truncated file registry can leave the database
         * unopenable - never do this for a database whose contents must survive.
         *
         * When this method is not called at all, the parameter is left unset and YouTrackDB's
         * default (`fsync` on) - or the process-wide [GlobalConfiguration] value - applies.
         * Note that calling it with either value pins the setting for this database and therefore
         * overrides any process-wide [GlobalConfiguration.STORAGE_CALL_FSYNC] value.
         *
         * A [withConfigBuilder] block still wins, as it is applied last.
         */
        fun withCallFsync(callFsync: Boolean) = apply {
            this.callFsync = callFsync
        }

        fun build(): YTDBDatabaseParams {
            return YTDBDatabaseParams(
                databasePath,
                databaseName,
                databaseType,
                appUser,
                additionalUsers.toList(),
                encryptionKey,
                closeDatabaseInDbProvider,
                closeAfterDelayTimeout,
                serverParams,
                configBuilder,
                transactionalIndexCreation,
                callFsync
            )
        }

        private fun Long.toByteArray(): ByteArray {
            return ByteArray(8) { i ->
                (this shr (i * 8) and 0xFF).toByte()
            }
        }
    }
}

data class YTDBUser(
    val name: String,
    val password: String,
    val role: String
) {
    init {
        require(name.isNotBlank()) { "User name must not be blank" }
        require(password.isNotBlank()) { "User password must not be blank" }
        require(name.matches(Regex("^[a-zA-Z0-9]*$"))) { "User name must contain only alphanumeric characters" }
        require(role == "admin" || role == "writer" || role == "reader") { "User role must be one of: admin, writer, reader" }
    }
}

data class YTDBServerParams(
    val serverConnectUser: String? = null,
    val serverConnectPassword: String? = null,
    val httpEnabled: Boolean = false,
    val httpBindAddress: String = "127.0.0.1",
    val httpPortRange: Pair<Int, Int> = Pair(2480, 2490),
    val binaryEnabled: Boolean = false,
    val binaryPortRange: Pair<Int, Int> = Pair(2424, 2430),
    val binaryBindAddress: String = "127.0.0.1",
    val logConsoleLevel: String = "info",
    val logFileLevel: String = "fine",
)
