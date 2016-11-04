package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.util.XdHierarchyNode
import kotlinx.dnq.util.parent
import org.reflections.Reflections
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import java.net.URL
import java.util.*
import javax.servlet.ServletContext
import kotlin.reflect.companionObjectInstance

object XdModel {
    const val JAVA_CLASSPATH = "java_classpath"
    const val WEB_CLASSPATH = "web_classpath"

    private val monitor = Object()
    private val scannedLocations = HashSet<String>()
    val hierarchy = HashMap<String, XdHierarchyNode>()

    operator fun get(entityType: XdEntityType<*>) = get(entityType.entityType)

    operator fun get(entityType: String) = hierarchy[entityType]

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

        allEntityClasses.asSequence().map {
            it.kotlin.companionObjectInstance
        }.filterIsInstance<XdEntityType<*>>().forEach {
            registerNode(it)
        }
    }

    fun registerNode(entityType: XdEntityType<*>): XdHierarchyNode {
        return hierarchy[entityType.entityType] ?: run {
            val parentNode = entityType.parent?.let { parent -> registerNode(parent) }
            XdHierarchyNode(entityType, parentNode).apply {
                hierarchy[entityType.entityType] = this
            }
        }
    }

    fun getOrThrow(entityType: String): XdHierarchyNode {
        return XdModel[entityType] ?: throw XdWrapperNotFoundException(entityType)
    }

    fun wrap(entity: Entity): XdEntity {
        val hierarchyNode = XdModel.getOrThrow(entity.type)
        return hierarchyNode.entityType.wrap(entity)
    }
}

