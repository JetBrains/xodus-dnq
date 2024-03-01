package kotlinx.dnq.orientdb

import com.orientechnologies.orient.core.db.ODatabaseSession
import com.orientechnologies.orient.core.metadata.schema.OClass
import com.orientechnologies.orient.core.metadata.schema.OClass.INDEX_TYPE
import com.orientechnologies.orient.core.metadata.schema.OProperty
import com.orientechnologies.orient.core.metadata.schema.OType
import com.orientechnologies.orient.core.record.ODirection
import com.orientechnologies.orient.core.record.OVertex
import jetbrains.exodus.query.metadata.*
import kotlinx.dnq.XdEnumEntity
import kotlinx.dnq.XdEnumEntityType
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.link.XdToOneOptionalLink
import kotlinx.dnq.simple.*
import kotlinx.dnq.singleton.XdSingletonEntityType
import kotlinx.dnq.util.XdHierarchyNode
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

class DnqSchemaToOrientDB(
    private val dnqModel: ModelMetaDataImpl,
    private val xdHierarchy: Map<String, XdHierarchyNode>,
    private val oSession: ODatabaseSession,
) {
    companion object {
        val BINARY_BLOB_CLASS_NAME: String = "BinaryBlob"
        val DATA_PROPERTY_NAME = "data"

        val STRING_BLOB_CLASS_NAME: String = "StringBlob"
    }

    private val paddedLogger = PaddedLogger(log)

    val indicesCreator = DeferredIndicesCreator()

    private fun withPadding(code: () -> Unit) = paddedLogger.withPadding(4, code)

    private fun append(s: String) = paddedLogger.append(s)

    private fun appendLine(s: String = "") = paddedLogger.appendLine(s)

    fun apply() {
        try {
            appendLine("applying the DNQ schema to OrientDB")
            appendLine("creating classes if absent:")
            withPadding {
                for (dnqEntity in dnqModel.entitiesMetaData) {
                    createVertexClassIfAbsent(dnqEntity)
                }
            }
            appendLine("creating properties and connections if absent:")
            withPadding {
                for (dnqEntity in dnqModel.entitiesMetaData) {
                    createPropertiesAndConnectionsIfAbsent(dnqEntity)
                }
            }

            // initialize enums and singletons

            appendLine("indices found:")
            withPadding {
                for ((indexOwner, indices) in indicesCreator.getIndices()) {
                    appendLine("$indexOwner:")
                    withPadding {
                        for (index in indices) {
                            appendLine(index.indexName)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            paddedLogger.flush()
            log.error(e) { e.message }
        } finally {
            paddedLogger.flush()
        }
    }

    private fun createVertexClassIfAbsent(dnqEntity: EntityMetaData) {
        append(dnqEntity.type)
        oSession.createVertexClassIfAbsent(dnqEntity.type)
        appendLine()

        /*
        * It is more efficient to create indices after the data migration.
        * So, we only remember indices here and let the user create them later.
        * */
        for (index in dnqEntity.ownIndexes.map { DeferredIndex(it, unique = true)}) {
            index.requireAllFieldsAreSimpleProperty()
            indicesCreator.add(index)
        }

        /*
        * Interfaces
        *
        * On the one hand, interfaces are in use in the query logic, see jetbrains.exodus.query.Utils.isTypeOf(...).
        * On the other hand, interfaces are not initialized anywhere, so EntityMetaData.interfaceTypes are always empty.
        *
        * So here, we ignore interfaces and do not try to apply them anyhow to OrientDB schema.
        * */
    }

    private fun ODatabaseSession.createVertexClassIfAbsent(name: String): OClass {
        var oClass: OClass? = getClass(name)
        if (oClass == null) {
            oClass = oSession.createVertexClass(name)!!
            append(", created")
        } else {
            append(", already created")
        }
        return oClass
    }

    private fun createPropertiesAndConnectionsIfAbsent(dnqEntity: EntityMetaData) {
        appendLine(dnqEntity.type)

        val xdNode = xdHierarchy.getValue(dnqEntity.type)
        val oClass = oSession.getClass(dnqEntity.type)

        withPadding {
            // superclass
            oClass.applySuperClass(dnqEntity.superType)

            // simple properties
            appendLine("simple properties:")
            withPadding {
                for ((_, simpleProperty) in xdNode.simpleProperties) {
                    oClass.applySimpleProperty(simpleProperty)
                }
            }

            // link properties
            appendLine("link properties:")
            withPadding {
                for ((_, linkProperty) in xdNode.linkProperties) {
                    oClass.applyLinkProperty(linkProperty)
                }
            }
        }
    }

    private fun OClass.applySuperClass(superClassName: String?) {
        if (superClassName == null) {
            append("no super type")
        } else {
            append("super type is $superClassName")
            val superClass = oSession.getClass(superClassName)
            if (superClasses.contains(superClass)) {
                append(", already set")
            } else {
                addSuperClass(superClass)
                append(", set")
            }
        }
        appendLine()
    }

    private fun OClass.applyLinkProperty(linkProperty: XdHierarchyNode.LinkProperty) {
        val propertyName = linkProperty.dbPropertyName
        val property = linkProperty.delegate
        append(propertyName)

        when (property) {
            is XdToOneOptionalLink<*, *> -> {
                val outClass = this
                val inClass = oSession.getClass(property.oppositeEntityType.entityType) ?: throw IllegalStateException("Opposite class not found. Happy debugging!")

                val edgeClassName = "${outClass.name}_${inClass.name}_$propertyName"
                oSession.createEdgeClass(edgeClassName)

                val outProperty = outClass.createProperty(
                    OVertex.getDirectEdgeLinkFieldName(ODirection.OUT, edgeClassName),
                    OType.LINKBAG
                )

                val inProperty = inClass.createProperty(
                    OVertex.getDirectEdgeLinkFieldName(ODirection.IN, edgeClassName),
                    OType.LINKBAG
                )

                // Person out[0..1] --> Link
                outProperty.setMandatory(false)
                outProperty.setMin("0")
                outProperty.setMax("1")

                // Link --> in[0..1] Car
                inProperty.setMandatory(false)
                inProperty.setMin("0")
                inProperty.setMax("1")
            }
            else -> throw IllegalArgumentException("$property is not supported. Feel free to support it.")
        }
    }

    private fun OClass.applySimpleProperty(simpleProperty: XdHierarchyNode.SimpleProperty) {
        val propertyName = simpleProperty.dbPropertyName
        var property = simpleProperty.delegate
        append(propertyName)
        // unwrap
        while (property is XdWrappedProperty<*, *, *>) {
            property = property.wrapped
        }
        when (property) {
            is XdProperty<*, *> -> {
                val oProperty = createPropertyIfAbsent(propertyName, getOType(property.clazz))

                oProperty.setNotNullIfDifferent(true)
                oProperty.setRequirement(property.requirement)

                /*
                * Default values
                *
                * Default values are implemented in DNQ as lambda functions that require
                * the entity itself and an instance of a KProperty to be called.
                *
                * So, it is not as straightforward as one may want to extract the default value out
                * of this lambda.
                *
                * So, a hard decision was made in this regard - ignore the default values on the
                * schema mapping step and handle them on the query processing level.
                *
                * Feel free to support default values in Schema mapping if you want to.
                * */

                /*
                * Constraints
                *
                * There are some typed constraints, and that is good.
                * But there are some anonymous constraints, and that is not good.
                * Most probably, there are constraints we do not know any idea of existing
                * (users can define their own constraints without any restrictions), and that is bad.
                *
                * Despite being able to map SOME constraints to the schema, there still will be
                * constraints we can not map (anonymous or user-defined).
                *
                * So, checking constraints on the query level is required.
                *
                * So, we made one of the hardest decisions in our lives and decided not to map
                * any of them at the schema mapping level.
                *
                * Feel free to do anything you want in this regard.
                * */
            }
            is XdNullableProperty<*, *> -> {
                val oProperty = createPropertyIfAbsent(propertyName, getOType(property.clazz))

                oProperty.setNotNullIfDifferent(false)
                oProperty.setRequirement(property.requirement)
            }
            is XdSetProperty<*, *> -> {
                val oProperty = createEmbeddedSetPropertyIfAbsent(propertyName, getOType(property.clazz))

                /*
                * If the value is not defined, the property returns true.
                * It is handled on the DNQ entities level.
                * */
                oProperty.setNotNullIfDifferent(false)
                oProperty.setRequirement(XdPropertyRequirement.OPTIONAL)

                /*
                * When creating an index on an EMBEDDEDSET field, OrientDB does not create an index for the field itself.
                * Instead, it creates an index for each individual item in the set.
                * This is done to enable quick searches for individual elements within the set.
                *
                * The same behaviour as the original behaviour of set properties in DNQ.
                * */
                val index = makeDeferredIndexForEmbeddedSet(propertyName)
                indicesCreator.add(index)
            }
            is XdMutableSetProperty<*, *> -> {
                // the same as XdSetProperty<*, *>, look above

                val oProperty = createEmbeddedSetPropertyIfAbsent(propertyName, getOType(property.clazz))
                oProperty.setNotNullIfDifferent(false)
                oProperty.setRequirement(XdPropertyRequirement.OPTIONAL)

                val index = makeDeferredIndexForEmbeddedSet(propertyName)
                indicesCreator.add(index)
            }
            is XdNullableBlobProperty -> {
                val oProperty = createBinaryBlobPropertyIfAbsent(propertyName)
                oProperty.setNotNullIfDifferent(false)
                oProperty.setRequirement(property.requirement)
            }
            is XdBlobProperty -> {
                val oProperty = createBinaryBlobPropertyIfAbsent(propertyName)
                oProperty.setNotNullIfDifferent(true)
                oProperty.setRequirement(property.requirement)
            }
            is XdNullableTextProperty -> {
                val oProperty = createStringBlobPropertyIfAbsent(propertyName)
                oProperty.setNotNullIfDifferent(false)
                oProperty.setRequirement(property.requirement)
            }
            is XdTextProperty -> {
                val oProperty = createStringBlobPropertyIfAbsent(propertyName)
                oProperty.setNotNullIfDifferent(true)
                oProperty.setRequirement(property.requirement)
            }
            else -> throw IllegalArgumentException("$property is not supported. Feel free to support it.")
        }

        appendLine()
    }

    private fun OClass.createBinaryBlobPropertyIfAbsent(propertyName: String): OProperty = createBlobPropertyIfAbsent(propertyName, BINARY_BLOB_CLASS_NAME)

    private fun OClass.createStringBlobPropertyIfAbsent(propertyName: String): OProperty = createBlobPropertyIfAbsent(propertyName, STRING_BLOB_CLASS_NAME)

    private fun OClass.createBlobPropertyIfAbsent(propertyName: String, blobClassName: String): OProperty {
        val blobClass = oSession.createBlobClassIfAbsent(blobClassName)

        val oProperty = createPropertyIfAbsent(propertyName, OType.LINK)
        if (oProperty.linkedClass != blobClass) {
            oProperty.setLinkedClass(blobClass)
        }
        require(oProperty.linkedClass == blobClass) { "Property linked class is ${oProperty.linkedClass}, but $blobClass was expected" }
        return oProperty
    }

    private fun ODatabaseSession.createBlobClassIfAbsent(className: String): OClass {
        var oClass: OClass? = getClass(className)
        if (oClass == null) {
            oClass = oSession.createVertexClass(className)!!
            append(", $className class created")
            oClass.createProperty(DATA_PROPERTY_NAME, OType.BINARY)
            append(", $DATA_PROPERTY_NAME property created")
        } else {
            append(", $className class already created")
            require(oClass.existsProperty(DATA_PROPERTY_NAME)) { "$DATA_PROPERTY_NAME is missing in $className, something went dramatically wrong. Happy debugging!" }
        }
        return oClass
    }

    private fun OProperty.setRequirement(requirement: XdPropertyRequirement) {
        when (requirement) {
            XdPropertyRequirement.OPTIONAL -> {
                append(", optional")
                if (isMandatory) {
                    isMandatory = false
                }
            }
            XdPropertyRequirement.REQUIRED -> {
                append(", required")
                if (!isMandatory) {
                    isMandatory = true
                }
            }
            XdPropertyRequirement.UNIQUE -> {
                append(", required, unique")
                if (!isMandatory) {
                    isMandatory = true
                }
                createIndexIfAbsent(INDEX_TYPE.UNIQUE)
            }
        }
    }

    private fun OProperty.createIndexIfAbsent(indexType: INDEX_TYPE) {
        if (allIndexes.all { it.type != indexType.name }) {
            createIndex(indexType)
        }
        require(allIndexes.any { it.type == indexType.name })
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

    private fun OClass.createEmbeddedSetPropertyIfAbsent(propertyName: String, oType: OType): OProperty {
        append(", type of the set is $oType")
        val oProperty = if (existsProperty(propertyName)) {
            append(", already created")
            getProperty(propertyName)
        } else {
            append(", created")
            createProperty(propertyName, OType.EMBEDDEDSET, oType)
        }
        require(oProperty.type == OType.EMBEDDEDSET) { "$propertyName type is ${oProperty.type} but ${OType.EMBEDDEDSET} was expected instead. Types migration is not supported."  }
        require(oProperty.linkedType == oType) { "$propertyName type of the set is ${oProperty.type} but $oType was expected instead. Types migration is not supported." }
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