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
package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.TransientEntityImpl
import jetbrains.exodus.core.dataStructures.LongObjectCacheBase
import jetbrains.exodus.core.dataStructures.SoftConcurrentLongObjectCache
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.management.DnqStatistics
import kotlinx.dnq.query.FakeTransientEntity
import kotlinx.dnq.util.XdHierarchyNode
import kotlinx.dnq.util.entityType
import kotlinx.dnq.util.parent
import mu.KLogging
import org.reflections.Reflections
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import java.net.URL
import java.util.*
import javax.servlet.ServletContext
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.full.getExtensionDelegate
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.jvmErasure

object XdModel : KLogging() {
    const val JAVA_CLASSPATH = "java_classpath"
    const val WEB_CLASSPATH = "web_classpath"

    private val monitor = Object()
    private val scannedLocations = HashSet<String>()
    val hierarchy = HashMap<String, XdHierarchyNode>()
    internal val plugins = ArrayList<XdModelPlugin>()
    private val toXdCache: LongObjectCacheBase<ToXdCachedValue> =
            SoftConcurrentLongObjectCache(System.getProperty("kotlinx.dnq.model.toXdCacheSize", "10000").toInt())

    operator fun get(entityType: XdEntityType<*>) = get(entityType.entityType)

    operator fun get(entityType: String) = hierarchy[entityType]

    fun scanPackages(packages: Array<String>) = scanClasspath(JAVA_CLASSPATH) {
        forPackages(*packages)
    }

    fun exposeJMX(applicationName: String) {
        DnqStatistics().register(applicationName)
    }

    /**
     * Scans Java classpath for XdEntity types
     */
    fun scanJavaClasspath() = scanClasspath(JAVA_CLASSPATH) {
        addUrls(ClasspathHelper.forJavaClassPath())
    }

    fun scanWebClasspath(servletContext: ServletContext) = scanClasspath(WEB_CLASSPATH) {
        ClasspathHelper.forWebInfClasses(servletContext)?.let {
            addUrls(it)
        }
        ClasspathHelper.forWebInfLib(servletContext)?.let {
            addUrls(it)
        }
    }

    fun scanURLs(locationID: String, urls: Array<URL>) = scanClasspath(locationID) {
        addUrls(*urls)
    }

    private fun scanClasspath(locationID: String, configure: ConfigurationBuilder.() -> Unit) = synchronized(monitor) {
        if (locationID in scannedLocations) return
        scannedLocations.add(locationID)

        val reflections = Reflections(ConfigurationBuilder().apply { configure() })
        val allEntityClasses = reflections.getSubTypesOf(XdEntity::class.java)

        allEntityClasses.forEach {
            if (XdEntity::class.java.isAssignableFrom(it) && it != XdEntity::class.java) {
                registerNode(it.entityType)
            }
        }
    }

    fun registerNode(entityType: XdEntityType<*>): XdHierarchyNode = hierarchy.getOrPut(entityType.entityType) {

        val parentNode = entityType.parent?.let { registerNode(it) }
        XdHierarchyNode(entityType, parentNode)
    }

    fun withPlugins(modelPlugins: XdModelPlugins) {
        modelPlugins.plugins.forEach { installPlugin(it) }
    }

    private fun installPlugin(plugin: XdModelPlugin) {
        plugins.add(plugin)
        plugin.typeExtensions.forEach {
            val delegate = try {
                it.isAccessible = true
                it.getExtensionDelegate()
            } catch (e: Throwable) {
                logger.warn(e) { "can't get extension delegate of '$it'" }
                null
            }
            delegate
                    ?: throw UnsupportedOperationException("Property $it cannot be registered because it is not extension property. " +
                            "Not extension properties are registered based on XdEntities fields")
            val receiverClass = it.extensionReceiverParameter?.type?.jvmErasure?.java
            if (receiverClass != null && XdEntity::class.java.isAssignableFrom(receiverClass)) {
                @Suppress("UNCHECKED_CAST")
                val entityType = (receiverClass as Class<XdEntity>).entityType
                get(entityType)?.process(it, delegate)
                        ?: throw UnsupportedOperationException("Property $it cannot be registered because of unknown delegate")
            } else {
                throw UnsupportedOperationException("Property $it cannot be registered because receiver of incorrect receiver class $receiverClass")
            }
        }
    }


    fun registerNodes(vararg entityTypes: XdEntityType<*>) = entityTypes.map { registerNode(it) }

    fun getOrThrow(entityType: String): XdHierarchyNode {
        return XdModel[entityType] ?: throw XdWrapperNotFoundException(entityType)
    }

    @Deprecated("Use toXd(entity) instead. May be removed after 01.09.2017", ReplaceWith("toXd(entity)"))
    fun wrap(entity: Entity): XdEntity {
        val hierarchyNode = getOrThrow(entity.type)
        return hierarchyNode.entityType.wrap(entity)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : XdEntity> toXd(entity: Entity): T {
        // this is hack to support abstract type in XdEntityType.filter {} api
        if (entity is FakeTransientEntity) {
            return entity.toXdHandlingAbstraction()
        }

        if (entity is TransientEntityImpl && !entity.isReadonly /* filter entities created over snapshot transaction */) {
            toXdCache.tryKey(entity.cacheKey)?.let { cachedValue ->
                if (cachedValue.entity == entity) {
                    return cachedValue.xdEntity as T
                }
            }
        }

        val xdHierarchyNode = getOrThrow(entity.type)
        val entityType = xdHierarchyNode.entityType
        if (entityType is XdNaturalWrapper) {
            return entityType.naturalWrap(entity).asCached as T
        }

        val entityConstructor = xdHierarchyNode.entityConstructor
                ?: throw UnsupportedOperationException("Constructor for the type ${entity.type} is not found")

        return entityConstructor(entity).asCached as T

    }

    fun <T : XdEntity> getCommonAncestor(typeA: XdEntityType<T>, typeB: XdEntityType<T>): XdEntityType<T>? {
        if (typeA == typeB) return typeA

        val nodeA = getOrThrow(typeA.entityType)
        val nodeB = getOrThrow(typeB.entityType)

        val parentsA = generateSequence(nodeA) { it.parentNode }.toSet()
        generateSequence(nodeB) { it.parentNode }.forEach { node ->
            @Suppress("UNCHECKED_CAST")
            if (node in parentsA) return node.entityType as XdEntityType<T>
        }

        return null
    }

    val toXdCacheSize: Int get() = toXdCache.size()

    val toXdCacheHitRate: Float get() = toXdCache.hitRate()

    private val XdEntity.asCached: XdEntity
        get() {
            if (entity is TransientEntityImpl && !entity.isReadonly  /* filter entities created over snapshot transaction */) {
                toXdCache.cacheObject(entity.cacheKey, ToXdCachedValue(entity, this))
            }
            return this
        }

    private val TransientEntityImpl.cacheKey get() = (store.persistentStore.hashCode() shl 32) + persistentEntity.hashCode().toLong()

    private class ToXdCachedValue(val entity: TransientEntityImpl, val xdEntity: XdEntity)
}

