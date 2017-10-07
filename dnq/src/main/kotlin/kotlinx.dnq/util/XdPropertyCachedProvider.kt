package kotlinx.dnq.util

import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.XdConstrainedProperty
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty

class XdPropertyCachedProvider<in R : XdEntity, T>(private val create: () -> XdConstrainedProperty<R, T>) {
    companion object {
        val cache = ConcurrentHashMap<KProperty<*>, XdConstrainedProperty<*, *>>()

        fun <V : XdConstrainedProperty<*, *>> getOrPut(prop: KProperty<*>, defaultValue: () -> V): V {
            @Suppress("UNCHECKED_CAST")
            return cache.getOrPut(prop, defaultValue) as V
        }
    }

    operator fun provideDelegate(thisRef: R, prop: KProperty<*>) = getOrPut(prop, create)
}
