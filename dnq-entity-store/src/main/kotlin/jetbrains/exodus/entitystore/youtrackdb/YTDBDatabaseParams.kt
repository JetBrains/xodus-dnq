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
     * Dual-mode index creation (XD-1283). When false (the default), indices are created on
     * YTDB's legacy non-transactional path (createIndex + fillIndex over committed rows).
     * When true, index creation runs inside explicit transactions - which is rejected at
     * commit for populated classes until YTDB-1064 is lifted.
     *
     * The default flips to true (and this flag retires) when YTDB-1064 is lifted.
     */
    val transactionalIndexCreation: Boolean = false
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
        private var transactionalIndexCreation: Boolean = false

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

        /** See [YTDBDatabaseParams.transactionalIndexCreation]. */
        fun withTransactionalIndexCreation(transactionalIndexCreation: Boolean) = apply {
            this.transactionalIndexCreation = transactionalIndexCreation
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
                transactionalIndexCreation
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
