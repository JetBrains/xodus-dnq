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
package com.jetbrains.teamsys.dnq.database

import io.github.oshai.kotlinlogging.KotlinLogging
import jetbrains.exodus.database.TransientChangesTracker
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import javax.management.InstanceNotFoundException
import javax.management.ObjectName

private const val OBJECT_NAME_PREFIX = "kotlinx.dnq: type=TransactionSizeWarning"
private const val DEFAULT_THRESHOLD = 500
private const val MAX_DETAILS_PER_TYPE = 10

class TransactionSizeWarning : TransactionSizeWarningMBean {

    companion object {
        val logger = KotlinLogging.logger {}
    }

    @Volatile
    override var enabled: Boolean = false

    @Volatile
    override var warningThreshold: Int = DEFAULT_THRESHOLD

    private val _warningCount = AtomicLong(0)
    override val warningCount: Long get() = _warningCount.get()

    private var registeredName: ObjectName? = null

    fun register(applicationName: String) {
        try {
            val name = ObjectName("$OBJECT_NAME_PREFIX, app = $applicationName")
            ManagementFactory.getPlatformMBeanServer().registerMBean(this, name)
            registeredName = name
        } catch (e: Exception) {
            logger.warn(e) { "error registering TransactionSizeWarning mbean" }
        }
    }

    fun unregister() {
        val name = registeredName ?: return
        try {
            ManagementFactory.getPlatformMBeanServer().unregisterMBean(name)
        } catch (_: InstanceNotFoundException) {
        } catch (e: Exception) {
            logger.warn(e) { "error unregistering TransactionSizeWarning mbean" }
        }
        registeredName = null
    }

    fun checkAndWarn(tracker: TransientChangesTracker) {
        if (!enabled) return

        val changedCount = tracker.changedEntities.size
        if (changedCount <= warningThreshold) return

        _warningCount.incrementAndGet()

        if (!logger.isWarnEnabled()) return

        val entitiesByType = tracker.changedEntities.groupBy { it.type }

        val breakdown = entitiesByType
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .joinToString { "${it.key}:${it.value}" }

        val details = entitiesByType.entries
            .sortedByDescending { it.value.size }
            .joinToString("\n") { (type, entities) ->
                val entity = entities.first()
                val props = tracker.getChangedProperties(entity)
                    ?.take(MAX_DETAILS_PER_TYPE)?.joinToString().orEmpty()
                val links = tracker.getChangedLinksDetailed(entity)
                    ?.keys?.take(MAX_DETAILS_PER_TYPE)?.joinToString().orEmpty()
                "  $type: properties=[$props], links=[$links]"
            }

        logger.warn { "Transaction updates $changedCount entities (threshold: $warningThreshold). " +
                "Entity types: [$breakdown]\n$details" }
    }
}