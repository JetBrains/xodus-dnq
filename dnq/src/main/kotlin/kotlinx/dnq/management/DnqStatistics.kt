package kotlinx.dnq.management

import jetbrains.exodus.core.dataStructures.ObjectCacheBase
import kotlinx.dnq.util.XdPropertyCachedProvider
import mu.KLogging
import java.lang.management.ManagementFactory
import javax.management.ObjectName

const val OBJECT_NAME_PREFIX = "kotlinx.dnq: type=DnqMBean"

class DnqStatistics : DnqStatisticsMBean {

    companion object : KLogging()

    override val delegatesCacheSize: Int
        get() = XdPropertyCachedProvider.cache.size()
    override val delegatesCacheHitRate: String
        get() = ObjectCacheBase.formatHitRate(XdPropertyCachedProvider.cache.hitRate())

    fun register(applicationName: String) {
        try {
            val name = ObjectName("$OBJECT_NAME_PREFIX, app = $applicationName")
            ManagementFactory.getPlatformMBeanServer().registerMBean(this, name)
        } catch (e: Exception) {
            logger.warn(e) { "error registering statistics mbean" }
        }

    }
}