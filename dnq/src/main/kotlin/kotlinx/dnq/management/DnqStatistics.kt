/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
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
package kotlinx.dnq.management

import kotlinx.dnq.XdModel
import kotlinx.dnq.util.XdPropertyCachedProvider
import mu.KLogging
import java.lang.management.ManagementFactory
import javax.management.ObjectName

const val OBJECT_NAME_PREFIX = "kotlinx.dnq: type=DnqStatistics"

class DnqStatistics : DnqStatisticsMBean {

    companion object : KLogging()

    override val delegatesCacheSize: Int
        get() = XdPropertyCachedProvider.cache.size()

    override val delegatesCacheHitRate: Float
        get() = XdPropertyCachedProvider.cache.hitRate()

    override val toXdCacheSize: Int
        get() = XdModel.toXdCacheSize

    override val toXdCacheHitRate: Float
        get() = XdModel.toXdCacheHitRate

    fun register(applicationName: String) {
        try {
            val name = ObjectName("$OBJECT_NAME_PREFIX, app = $applicationName")
            ManagementFactory.getPlatformMBeanServer().registerMBean(this, name)
        } catch (e: Exception) {
            logger.warn(e) { "error registering statistics mbean" }
        }

    }
}