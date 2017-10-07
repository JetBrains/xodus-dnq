package kotlinx.dnq.util

import kotlinx.dnq.XdEntity
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty

class XdPropertyCachedProvider<out D>(private val create: () -> D) {
    companion object {
        val cache = ConcurrentHashMap<KProperty<*>, Any>()
    }

    operator fun provideDelegate(thisRef: XdEntity, prop: KProperty<*>): D {
        @Suppress("UNCHECKED_CAST")
        return cache.getOrPut(prop, create) as D
    }
}
