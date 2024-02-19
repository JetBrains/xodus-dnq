package kotlinx.dnq.orientdb

import com.orientechnologies.orient.core.db.ODatabaseSession
import com.orientechnologies.orient.core.metadata.schema.OClass
import com.orientechnologies.orient.core.metadata.schema.OProperty
import com.orientechnologies.orient.core.metadata.schema.OType
import jetbrains.exodus.query.metadata.EntityMetaData
import jetbrains.exodus.query.metadata.ModelMetaDataImpl
import kotlinx.dnq.XdEnumEntity
import kotlinx.dnq.XdEnumEntityType
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.simple.XdNullableProperty
import kotlinx.dnq.simple.XdProperty
import kotlinx.dnq.simple.XdPropertyRequirement
import kotlinx.dnq.simple.XdWrappedProperty
import kotlinx.dnq.singleton.XdSingletonEntityType
import kotlinx.dnq.util.XdHierarchyNode
import mu.KLogger
import mu.KLogging

class DnqSchemaToOrientDB(
    private val dnqModel: ModelMetaDataImpl,
    private val xdHierarchy: Map<String, XdHierarchyNode>,
    private val oSession: ODatabaseSession,
) {
    companion object: KLogging()

    private val paddedLogger = PaddedLogger(logger)

    private fun withPadding(padding: Int = 4, code: () -> Unit) {
        paddedLogger.updatePadding(padding)
        code()
        paddedLogger.updatePadding(-padding)
    }

    private fun append(s: String) {
        paddedLogger.append(s)
    }

    private fun appendLine(s: String = "") {
        paddedLogger.appendLine(s)
    }

    fun apply() {
        try {
            appendLine("applying the DNQ schema to OrientDB")
            appendLine("creating classes if absent:")
            withPadding {
                for (dnqEntity in dnqModel.entitiesMetaData) {
                    createClassIfAbsent(dnqEntity)
                }
            }
            appendLine("creating properties and connections if absent:")
            withPadding {
                for (dnqEntity in dnqModel.entitiesMetaData) {
                    createPropertiesAndConnectionsIfAbsent(dnqEntity)
                }
            }
            // initialize enums and singletons
        } catch (e: Throwable) {
            paddedLogger.flush()
            logger.error(e) { e.message }
        } finally {
            paddedLogger.flush()
        }
    }

    private fun ignored(entityTypeName: String): Boolean {
        /*
        * Enum classes in OrientDb will not be inherited from a single super class.
        * So, we ignore the XodusDNQ enum superclass.
        *
        * If one finds why having a single super class for enum classes in OrientDB may be useful, feel free to change the code accordingly.
        * */
        return entityTypeName == XdEnumEntity.entityType
    }

    private fun createClassIfAbsent(dnqEntity: EntityMetaData) {
        append(dnqEntity.type)
        if (ignored(dnqEntity.type)) {
            appendLine(", ignored")
            return
        }

        val xdNode = xdHierarchy.getValue(dnqEntity.type)

        when (val entityType = xdNode.entityType) {
            is XdEnumEntityType<*> -> {
                append(", enum")
                require(!dnqEntity.isAbstract) { "An enum entity ${dnqEntity.type} is abstract. If you believe that an enum entity can be abstract, fix the code accordingly." }
                oSession.createClassIfAbsent(dnqEntity.type)
            }

            is XdSingletonEntityType<*> -> {
                append(", singleton")
                require(!dnqEntity.isAbstract) { "A singleton entity ${dnqEntity.type} is abstract. If you believe that a singleton entity can be abstract, fix the code accordingly." }
                oSession.createClassIfAbsent(dnqEntity.type)
            }

            is XdNaturalEntityType<*> -> {
                append(", natural entity")
                val oClass = oSession.createClassIfAbsent(dnqEntity.type)
                if (dnqEntity.isAbstract) {
                    if (!oClass.isAbstract) {
                        oClass.setAbstract(true)
                        append(", made abstract")
                    } else {
                        append(", already abstract")
                    }
                }
            }

            else -> throw IllegalArgumentException("Unknown entity type ${dnqEntity.type}:${entityType}")
        }
        appendLine()

        /*
        * Interfaces
        *
        * On the one hand, interfaces are in use in the query logic, see jetbrains.exodus.query.Utils.isTypeOf(...).
        * On the other hand, interfaces are not initialized anywhere, so EntityMetaData.interfaceTypes are always empty.
        *
        * So here, we ignore interfaces and do not try to apply them anyhow to OrientDB schema.
        * */
    }

    private fun ODatabaseSession.createClassIfAbsent(name: String): OClass {
        var oClass: OClass? = getClass(name)
        if (oClass == null) {
            oClass = oSession.createClass(name)!!
            append(", created")
        } else {
            append(", already created")
        }
        return oClass
    }

    private fun createPropertiesAndConnectionsIfAbsent(dnqEntity: EntityMetaData) {
        append(dnqEntity.type)
        if (ignored(dnqEntity.type)) {
            appendLine(", ignored")
            return
        }
        appendLine()

        val xdNode = xdHierarchy.getValue(dnqEntity.type)
        val oClass = oSession.getClass(dnqEntity.type)

        withPadding {
            // superclass
            val superClassName = dnqEntity.superType
            if (superClassName == null) {
                append("no super type")
            } else {
                append("super type is $superClassName")
                if (ignored(superClassName)) {
                    append(", ignored")
                } else {
                    val superClass = oSession.getClass(superClassName)
                    if (oClass.superClasses.contains(superClass)) {
                        append(", already set")
                    } else {
                        oClass.addSuperClass(superClass)
                        append(", set")
                    }
                }
            }
            appendLine()

            // simple properties
            appendLine("simple properties:")


            dnqEntity.propertiesMetaData.first().type
            withPadding {
                for ((_, simpleProperty) in xdNode.simpleProperties) {
                    val propertyName = simpleProperty.dbPropertyName
                    var property = simpleProperty.delegate
                    val propertyType = simpleProperty.delegate.propertyType
                    append(propertyName)
                    // unwrap
                    while (property is XdWrappedProperty<*, *, *>) {
                        property = property.wrapped
                    }
                    when (property) {
                        is XdProperty<*, *> -> {
                            val oProperty = oClass.createPropertyIfAbsent(propertyName, getOType(property.clazz))

                            oProperty.setNotNullIfDifferent(true)
                            oProperty.setRequirement(property.requirement)

                            // constraints
                            // default
                            for (constraint in property.constraints) {
                                when (constraint) {

                                }
                            }
                        }
                        is XdNullableProperty<*, *> -> {
                            val oProperty = oClass.createPropertyIfAbsent(propertyName, getOType(property.clazz))

                            oProperty.setNotNullIfDifferent(false)
                            oProperty.setRequirement(property.requirement)
                        }
                        else -> throw IllegalArgumentException("$property is not supported. Feel free to support it.")
                    }


                    appendLine()
                }
            }

        }
    }

    private fun OProperty.setRequirement(requirement: XdPropertyRequirement) {
        when (requirement) {
            XdPropertyRequirement.OPTIONAL -> {
                append(", optional")
                if (isMandatory) {
                    setMandatory(false)
                }
            }
            XdPropertyRequirement.REQUIRED -> {
                append(", required")
                if (!isMandatory) {
                    setMandatory(true)
                }
            }
            XdPropertyRequirement.UNIQUE -> {
                append(", required, unique")
                if (!isMandatory) {
                    setMandatory(true)
                }
                if (allIndexes.all { it.type != OClass.INDEX_TYPE.UNIQUE.name }) {
                    createIndex(OClass.INDEX_TYPE.UNIQUE)
                }
                require(allIndexes.any { it.type == OClass.INDEX_TYPE.UNIQUE.name })
            }
        }
    }

    private fun OProperty.setNotNullIfDifferent(notNull: Boolean) {
        if (notNull) {
            append(", not nullable")
            if (!isNotNull) {
                setNotNull(true)
            }
        } else {
            append(", nullable")
            if (isNotNull) {
                setNotNull(false)
            }
        }
    }

    private fun OClass.createPropertyIfAbsent(propertyName: String, oType: OType): OProperty {
        append(", type is $oType")
        val oProperty = if (existsProperty(propertyName)) {
            append(", already created")
            getProperty(propertyName)
        } else {
            append(", created")
            createProperty(propertyName, oType)
        }
        require(oProperty.type == oType) { "$propertyName type is ${oProperty.type} but $oType was expected instead. Types migration is not supported."  }
        return oProperty
    }

    private fun getOType(clazz: Class<*>): OType {
        return when (clazz) {
            java.lang.Boolean::class.java -> OType.BOOLEAN
            java.lang.String::class.java -> OType.STRING
            java.lang.Integer::class.java -> OType.INTEGER
            java.lang.Long::class.java -> OType.LONG
            java.lang.Byte::class.java -> OType.BYTE
            java.lang.Float::class.java -> OType.FLOAT
            java.lang.Double::class.java -> OType.DOUBLE
            java.lang.Short::class.java -> OType.SHORT
            // DataTime
            // DNQ stores datetime as number of milliseconds since 1970-01-01T00:00:00Z
            // todo clarify if it is ok
            Long::class.java -> OType.LONG
            else -> throw IllegalArgumentException("$clazz is not supported. Feel free to support it.")
        }
    }
}

class PaddedLogger(
    private val logger: KLogger
) {
    private var paddingCount: Int = 0
    private val sb = StringBuilder()

    private var newLine: Boolean = false

    fun append(s: String) {
        addPaddingIfNewLine()
        sb.append(s)
    }

    fun appendLine(s: String = "") {
        addPaddingIfNewLine()
        sb.appendLine(s)
        newLine = true
    }

    fun updatePadding(paddingShift: Int) {
        paddingCount += paddingShift
    }

    fun flush() {
        logger.info { sb.toString() }
        sb.clear()
        newLine = true
        paddingCount = 0
    }

    private fun addPaddingIfNewLine() {
        if (newLine) {
            sb.append(" ".repeat(paddingCount))
            newLine = false
        }
    }
}