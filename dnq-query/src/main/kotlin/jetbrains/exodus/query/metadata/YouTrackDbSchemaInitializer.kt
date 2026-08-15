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
package jetbrains.exodus.query.metadata

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException
import com.jetbrains.youtrackdb.internal.core.id.RecordId
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty
import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.LOCAL_ENTITY_ID_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.linkTargetEntityIdPropertyName
import jetbrains.exodus.entitystore.youtrackdb.ClassIdReservation
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.CLASS_ID_CUSTOM_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.CLASS_ID_SEQUENCE_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.localEntityIdSequenceName
import jetbrains.exodus.entitystore.youtrackdb.createSequencesIfAbsent
import jetbrains.exodus.entitystore.youtrackdb.setClassIdIfAbsent
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

internal data class SchemaApplicationResult(
    val indices: Map<String, Set<DeferredIndex>>,
    val newIndexedLinks: Map<String, Set<String>> // ClassName -> set of link names
)

/**
 * Folds the results of several schema steps applied to the same session into one, so that the
 * deferred indices and the complementary-property backfill of a whole batch are driven by a single
 * pass each (XD-1283 association batching, see `ModelMetaDataImpl.batchAssociations`). Both maps are
 * keyed by class name, so merging is a per-key union - two steps that touch the same class
 * contribute to the same entry instead of one overwriting the other.
 */
internal fun Iterable<SchemaApplicationResult>.merged(): SchemaApplicationResult {
    val indices = HashMap<String, MutableSet<DeferredIndex>>()
    val newIndexedLinks = HashMap<String, MutableSet<String>>()
    for (result in this) {
        for ((className, classIndices) in result.indices) {
            indices.getOrPut(className) { HashSet() }.addAll(classIndices)
        }
        for ((className, linkNames) in result.newIndexedLinks) {
            newIndexedLinks.getOrPut(className) { HashSet() }.addAll(linkNames)
        }
    }
    return SchemaApplicationResult(indices, newIndexedLinks)
}

internal fun DatabaseSessionEmbedded.applySchema(
    metaData: ModelMetaData,
    indexForEverySimpleProperty: Boolean = false,
    applyLinkCardinality: Boolean = true
): SchemaApplicationResult =
    applySchema(metaData.entitiesMetaData, indexForEverySimpleProperty, applyLinkCardinality)

internal fun DatabaseSessionEmbedded.applySchema(
    entitiesMetaData: Iterable<EntityMetaData>,
    indexForEverySimpleProperty: Boolean = false,
    applyLinkCardinality: Boolean = true
): SchemaApplicationResult {
    val initializer =
        YouTrackDbSchemaInitializer(
            entitiesMetaData,
            this,
            indexForEverySimpleProperty,
            applyLinkCardinality
        )
    return initializer.apply()
}

internal fun DatabaseSessionEmbedded.addAssociation(
    outEntityMetadata: EntityMetaData,
    association: AssociationEndMetaData,
    applyLinkCardinality: Boolean = true
): SchemaApplicationResult {
    val link = association.toLinkMetadata(outEntityMetadata.type)
    return addAssociation(
        link,
        outEntityMetadata.getIndicesContainingLink(link.name),
        applyLinkCardinality
    )
}

internal fun DatabaseSessionEmbedded.addAssociation(
    link: LinkMetadata,
    indicesContainingLink: List<Index>,
    applyLinkCardinality: Boolean = true
): SchemaApplicationResult {
    val initializer = YouTrackDbSchemaInitializer(
        listOf(),
        this,
        indexForEverySimpleProperty = false,
        applyLinkCardinality = applyLinkCardinality
    )
    return initializer.addAssociation(link, indicesContainingLink)
}

/**
 * The one schema path DNQ still runs NON-transactionally (XD-1283): YTDB forbids `dropProperty`
 * under any active transaction. Callers must not open a transaction around it - and must know the
 * accepted hazard that comes with it: a non-transactional schema write engages no metadata write
 * mutex, so a concurrent transaction that has already written schema clobbers this change when it
 * promotes its schema copy at commit (silently, and in the disjoint-class case with permanent
 * schema corruption). Full description and the conditions for lifting it are on
 * `YTDBModelMetaData.onRemoveAssociation`.
 */
internal fun DatabaseSessionEmbedded.removeAssociation(
    sourceClassName: String,
    targetClassName: String,
    associationName: String
) {
    removeAssociation(
        LinkMetadata(
            name = associationName,
            outClassName = sourceClassName,
            inClassName = targetClassName,
            // it is ignored
            cardinality = AssociationEndCardinality._1
        )
    )
}

internal fun DatabaseSessionEmbedded.removeAssociation(
    association: LinkMetadata
) {
    val initializer =
        YouTrackDbSchemaInitializer(
            listOf(),
            this,
            indexForEverySimpleProperty = false,
            applyLinkCardinality = false
        )
    initializer.removeAssociation(association)
}

internal data class LinkMetadata(
    val name: String,
    val outClassName: String,
    val inClassName: String,
    val cardinality: AssociationEndCardinality
)

internal fun AssociationEndMetaData.toLinkMetadata(outClassName: String): LinkMetadata =
    LinkMetadata(
        name = name,
        outClassName = outClassName,
        inClassName = oppositeEntityMetaData.type,
        cardinality = cardinality
    )

private fun EntityMetaData.getIndicesContainingLink(linkName: String): List<Index> {
    return indexes.filter { index -> index.fields.any { field -> field.name == linkName } }
}

internal class YouTrackDbSchemaInitializer(
    private val entitiesMetaData: Iterable<EntityMetaData>,
    private val oSession: DatabaseSessionEmbedded,
    private val indexForEverySimpleProperty: Boolean,
    private val applyLinkCardinality: Boolean
) {
    private val paddedLogger = PaddedLogger.logger(log)

    private fun withPadding(code: () -> Unit) = paddedLogger.withPadding(4, code)

    private fun append(s: String) = paddedLogger.append(s)

    private fun appendLine(s: String = "") = paddedLogger.appendLine(s)


    private val indices = HashMap<String, MutableSet<DeferredIndex>>()

    private val newIndexedLinks = HashMap<String, MutableSet<String>>()

    /**
     * Per-class memo for [holdsNoRecords], keyed by class name. Valid for the lifetime of one schema
     * step (this object is created per schema application / per association add) because DNQ writes
     * no data inside a schema step.
     */
    private val recordlessClasses = HashMap<String, Boolean>()

    private fun addIndex(index: DeferredIndex) {
        indices.getOrPut(index.ownerVertexName) { HashSet() }.add(index)
    }

    private fun simplePropertyIndex(entityName: String, propertyName: String): DeferredIndex {
        return DeferredIndex(entityName, setOf(propertyName), unique = false)
    }

    private fun linkUniqueIndex(edgeClassName: String): DeferredIndex {
        return DeferredIndex(
            edgeClassName,
            setOf(Edge.DIRECTION_IN, Edge.DIRECTION_OUT),
            unique = true
        )
    }

    fun apply(): SchemaApplicationResult {
        val start = System.currentTimeMillis()
        try {
            appendLine("applying the DNQ schema to OrientDB")
            val sortedEntities = entitiesMetaData.sortedTopologically()

            /*
             * All sequences the pass needs - the classId sequence and one localEntityId sequence
             * per entity type - are created up front in a SINGLE immediately-committed side
             * transaction (XD-1283 performance; it used to be one transaction per type, i.e. one
             * storage commit per type). This must happen before the pass's first DDL write: on a
             * genesis database it creates the OSequence class, and the reservation below reads the
             * classId sequence through a pooled side transaction that only sees committed records.
             */
            oSession.createSequencesIfAbsent(
                buildList {
                    add(CLASS_ID_SEQUENCE_NAME)
                    sortedEntities.forEach { add(localEntityIdSequenceName(it.type)) }
                }
            )
            /*
             * One reserved block of class ids for the whole pass instead of one sequence.next()
             * transaction per type. The count is taken from the COMMITTED schema, which is what
             * this transaction still sees: nothing has been written yet.
             */
            val classIdReservation = ClassIdReservation(
                sortedEntities.count { dnqEntity ->
                    oSession.schema.getClass(dnqEntity.type)
                        ?.getCustom(CLASS_ID_CUSTOM_PROPERTY_NAME) == null
                }
            )

            appendLine("creating classes if absent:")
            withPadding {
                /*
                * We want superclasses be created before subclasses.
                * So, process entities in the topological order.
                * */
                for (dnqEntity in sortedEntities) {
                    createVertexClassIfAbsent(dnqEntity, classIdReservation)
                }
            }

            appendLine("creating simple properties if absent:")
            withPadding {
                /*
                * It is necessary to process entities in the topologically sorted order here too.
                *
                * Consider Superclass1 and Subclass1: Superclass1. All the properties of
                * Superclass1 will be both in EntityMetaData of Superclass1 and EntityMetaData of Subclass1.
                *
                * We want to those properties be created for Superclass1 in OrientDB, so we have to
                * process Superclass1 before Subclass1. That is why we have to process entities in
                * the topologically sorted order.
                * */
                for (dnqEntity in sortedEntities) {
                    createSimplePropertiesIfAbsent(dnqEntity)
                }
            }

            appendLine("creating associations if absent:")
            withPadding {
                for (dnqEntity in sortedEntities) {
                    appendLine(dnqEntity.type)
                    withPadding {
                        for (associationEnd in dnqEntity.associationEndsMetaData) {
                            this.addLinkImpl(
                                associationEnd.toLinkMetadata(dnqEntity.type),
                                dnqEntity.getIndicesContainingLink(associationEnd.name)
                            )
                        }
                    }
                }
            }

            // initialize enums and singletons

            appendLine("indices found:")
            withPadding {
                for ((indexOwner, indices) in indices) {
                    appendLine("$indexOwner:")
                    withPadding {
                        for (index in indices) {
                            appendLine(index.indexName)
                        }
                    }
                }
            }

            return SchemaApplicationResult(
                indices = indices,
                newIndexedLinks
            )
        } finally {
            paddedLogger.flush()
            log.info("Schema initialization took ${System.currentTimeMillis() - start}ms")
        }
    }

    fun addAssociation(
        association: LinkMetadata,
        indicesContainingLink: List<Index>
    ): SchemaApplicationResult {
        try {
            appendLine("create association [${association.outClassName} -> ${association.name} -> ${association.inClassName}] if absent:")
            this.addLinkImpl(
                association,
                indicesContainingLink,
            )
            return SchemaApplicationResult(
                indices = indices,
                newIndexedLinks = newIndexedLinks
            )
        } finally {
            paddedLogger.flush()
        }
    }

    fun removeAssociation(association: LinkMetadata) {
        try {
            appendLine("remove association [${association.outClassName} -> ${association.name} -> ${association.inClassName}] if exists:")
            removeAssociationImpl(association)
        } finally {
            paddedLogger.flush()
        }
    }

    // Vertices and Edges

    private fun createVertexClassIfAbsent(
        dnqEntity: EntityMetaData,
        classIdReservation: ClassIdReservation
    ) {
        append(dnqEntity.type)
        val oClass = oSession.createVertexClassIfAbsent(dnqEntity.type)
        oClass.applySuperClass(dnqEntity.superType)
        appendLine()

        // the localEntityId sequence was created with all the others up front (see apply())
        oSession.setClassIdIfAbsent(oClass, classIdReservation)
        /*
        * We do not apply a unique index to the localEntityId property because indices in OrientDB are polymorphic.
        * So, you can not have the same value in a property in an instance of a superclass and in an instance of its subclass.
        * But it exactly what happens in the original Xodus.
        * */

        /*
        * It is more efficient to create indices after the data migration.
        * So, we only remember indices here and let the user create them later.
        *
        * We ignore here any indices that contain links. It is because Xodus adds links
        * when the schema is already initialized. So, having here an index that contains
        * a link that has not yet been added to the schema is a valid case.
        *
        * We add indices containing links when we add a link from the index.
        * */
        for (index in dnqEntity.ownIndexes.filter { it.fields.none { !it.isProperty } }) {
            val properties = index.fields.map { it.name }.toSet()

            addIndex(
                DeferredIndex(
                    dnqEntity.type,
                    properties,
                    unique = true
                )
            )
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

    private fun DatabaseSessionEmbedded.createVertexClassIfAbsent(name: String): SchemaClass {
        var oClass: SchemaClass? = schema.getClass(name)
        if (oClass == null) {
            oClass = oSession.schema.createVertexClass(name)!!
            append(", created")
        } else {
            append(", already created")
        }
        return oClass
    }

    private fun DatabaseSessionEmbedded.createEdgeClassIfAbsent(name: String): SchemaClass {
        val className = YTDBVertexEntity.edgeClassName(name)
        var oClass: SchemaClass? = schema.getClass(className)
        if (oClass == null) {
            /*
             * Concurrent-creation race tolerance (XD-1283): another session may commit the
             * same class between the existence check and createEdgeClass. The metadata write
             * mutex serializes schema transactions, so the loser's tx-local schema copy is
             * seeded after the winner's commit and the re-check sees the winner's class.
             */
            oClass = try {
                oSession.schema.createEdgeClass(className)!!
            } catch (e: SchemaException) {
                schema.getClass(className) ?: throw e
            }
            append(", edge class created")
        } else {
            append(", edge class already created")
        }
        return oClass
    }

    private fun SchemaClass.applySuperClass(superClassName: String?) {
        if (superClassName == null) {
            append(", no super type")
        } else {
            append(", super type is $superClassName")
            val superClass = oSession.schema.getClass(superClassName)
            if (superClasses.contains(superClass)) {
                append(", already set")
            } else {
                addSuperClass(superClass)
                append(", set")
            }
        }
    }


    // Links

    private fun addLinkImpl(
        link: LinkMetadata,
        indicesContainingLink: List<Index>,
    ) {
        append(link.name)

        val outClass = oSession.schema.getClass(link.outClassName)
            ?: throw IllegalStateException("${link.outClassName} class is not found")
        val inClass = oSession.schema.getClass(link.inClassName)
            ?: throw IllegalStateException("${link.inClassName} class is not found")

        val edgeClass = oSession.createEdgeClassIfAbsent(link.name)
        appendLine()

        withPadding {
            if (applyLinkCardinality) {
                applyLinkCardinality(
                    edgeClass,
                    outClass,
                    link.cardinality,
                    inClass,
                )
            }
            applyIndices(
                link.name,
                edgeClass,
                outClass,
                indicesContainingLink
            )
        }
    }

    private fun applyLinkCardinality(
        edgeClass: SchemaClass,
        outClass: SchemaClass,
        outCardinality: AssociationEndCardinality,
        inClass: SchemaClass,
    ) {
        val linkOutPropName = Vertex.getEdgeLinkFieldName(Direction.OUT, edgeClass.name)
        append("outProp: ${outClass.name}.$linkOutPropName")
        val outProp = outClass.createLinkPropertyIfAbsent(linkOutPropName)
        // applying cardinality only to out direct property
        outProp.applyCardinality(outCardinality)
        appendLine()

        val linkInPropName = Vertex.getEdgeLinkFieldName(Direction.IN, edgeClass.name)
        append("inProp: ${inClass.name}.$linkInPropName")
        inClass.createLinkPropertyIfAbsent(linkInPropName)
        appendLine()

        /*
        * We do not apply cardinality for the in-properties because, we do not know if there is any restrictions.
        * Because AssociationEndCardinality describes the cardinality of a single end.
        * */
    }

    private fun applyIndices(
        linkName: String,
        edgeClass: SchemaClass,
        outClass: SchemaClass,
        indicesContainingLink: List<Index>
    ) {
        addIndex(linkUniqueIndex(edgeClass.name))

        if (indicesContainingLink.isNotEmpty()) {
            val indexedPropName = linkTargetEntityIdPropertyName(linkName)
            append("prop for composite indices: ${outClass.name}.$indexedPropName")

            if (!outClass.existsProperty(indexedPropName)) {
                newIndexedLinks.getOrPut(outClass.name) { HashSet() }.add(linkName)
            }
            outClass.createPropertyIfAbsent(indexedPropName, PropertyType.LINKBAG)
        }

        for (index in indicesContainingLink) {
            val simpleProperties = index.fields.filter { it.isProperty }.map { it.name }.toSet()
            val linkComplementaryProperties = index.fields.filter { !it.isProperty }
                .map { linkTargetEntityIdPropertyName(it.name) }.toSet()
            val allIndexedProperties = simpleProperties + linkComplementaryProperties
            /*
             * The index belongs to the type that DECLARES it, not to the type being processed -
             * EntityMetaData.indexes includes the indexes inherited from super types, so a super
             * type's index shows up again for each of its sub types. Keying the deferred index on
             * the declaring type keeps one index per declaration (YTDB indexes are polymorphic, so
             * a sub-type copy would add coverage the super type's index already has) and matches
             * how simple-property indexes are collected (from ownIndexes, i.e. per declaring type).
             */
            val ownerClass = index.ownerEntityType?.let { oSession.schema.getClass(it) } ?: outClass
            // create the index only if all the containing properties are already initialized
            if (allIndexedProperties.all { ownerClass.existsProperty(it) }) {
                addIndex(
                    DeferredIndex(
                        ownerClass.name,
                        allIndexedProperties,
                        unique = true
                    )
                )
            }
        }

        appendLine()
    }

    private fun SchemaProperty.applyCardinality(cardinality: AssociationEndCardinality) {
        when (cardinality) {
            AssociationEndCardinality._0_1 -> {
                setRequirement(false)
                setMinIfDifferent("0")
                setMaxIfDifferent("1")
            }

            AssociationEndCardinality._1 -> {
                setRequirement(true)
                setMinIfDifferent("1")
                setMaxIfDifferent("1")
            }

            AssociationEndCardinality._0_n -> {
                setRequirement(false)
                setMinIfDifferent("0")
                setMaxIfDifferent(null)
            }

            AssociationEndCardinality._1_n -> {
                setRequirement(true)
                setMinIfDifferent("1")
                setMaxIfDifferent(null)
            }
        }
    }

    private fun SchemaProperty.setMaxIfDifferent(max: String?) {
        append(", max $max")
        if (this.max == max) {
            append(" already set")
        } else {
            setMax(max)
            append(" set")
        }
    }

    private fun SchemaProperty.setMinIfDifferent(min: String?) {
        append(", min $min")
        if (this.min == min) {
            append(" already set")
        } else {
            setMin(min)
            append(" set")
        }
    }

    private fun removeAssociationImpl(association: LinkMetadata) {
        removeEdge(association.outClassName, association.name, Direction.OUT)
        removeEdge(association.inClassName, association.name, Direction.IN)
    }

    private fun removeEdge(className: String, associationName: String, direction: Direction) {
        append(className)
        val sourceClass = oSession.schema.getClass(className)
        val edgeClassName = YTDBVertexEntity.edgeClassName(associationName)
        if (sourceClass != null) {
            val propOutName = Vertex.getEdgeLinkFieldName(direction, edgeClassName)
            append(".$propOutName")
            if (sourceClass.existsProperty(propOutName)) {
                sourceClass.dropProperty(propOutName)
                append(" deleted")
            } else {
                append(" not found")
            }
        } else {
            append(" not found")
        }
        appendLine()
    }


    // Simple properties

    private fun createSimplePropertiesIfAbsent(dnqEntity: EntityMetaData) {
        appendLine(dnqEntity.type)

        val oClass = oSession.schema.getClass(dnqEntity.type)

        withPadding {
            for (propertyMetaData in dnqEntity.propertiesMetaData) {
                if (propertyMetaData is PropertyMetaDataImpl) {
                    val required = propertyMetaData.name in dnqEntity.requiredProperties
                    /*
                     Xodus does not let a property be null/empty if it is in an index.
                     Check out TransientSessionImpl.checkBeforeSaveChangesConstraints() for details.
                     Xodus explicitly prohibits empty values for indexed simple properties (it throws more or less understandable exception).
                     Xodus implicitly prohibits empty values for indexed links (it crashes with null pointer exception).
                     */
                    val requiredBecauseOfIndex =
                        dnqEntity.ownIndexes.any { index -> index.fields.any { it.name == propertyMetaData.name } }
                    oClass.applySimpleProperty(propertyMetaData, required || requiredBecauseOfIndex)
                }
            }

            val prop = SimplePropertyMetaDataImpl(LOCAL_ENTITY_ID_PROPERTY_NAME, "long")
            oClass.applySimpleProperty(prop, true)
            // we need this index regardless what we have in indexForEverySimpleProperty
            // the index for localEntityId must not be unique, otherwise it will not let the same localEntityId
            // for subtypes of a supertype
            addIndex(simplePropertyIndex(dnqEntity.type, LOCAL_ENTITY_ID_PROPERTY_NAME))
        }
    }

    private fun SchemaClass.applySimpleProperty(
        simpleProp: PropertyMetaDataImpl,
        required: Boolean
    ) {
        val propertyName = simpleProp.name
        append(propertyName)

        when (simpleProp.type) {
            jetbrains.exodus.query.metadata.PropertyType.PRIMITIVE -> {
                require(simpleProp is SimplePropertyMetaDataImpl) { "$propertyName is a primitive property but it is not an instance of SimplePropertyMetaDataImpl. Happy fixing!" }
                val primitiveTypeName =
                    simpleProp.primitiveTypeName
                        ?: throw IllegalArgumentException("primitiveTypeName is null")

                if (primitiveTypeName.lowercase() == "set") {
                    append(", is not supported yet")
                    /*
                    * To support sets we have to:
                    * 1. On the Xodus repo level
                    *   1. Add SimplePropertyMetaDataImpl.argumentType: String? property (or list of them, it is easier to extend)
                    * 2. On the XodusDNQ repo level
                    *   1. DNQMetaDataUtil.kt, addEntityMetaData(), 119 line, fill that argumentType param
                    * 3. Support here
                    * */
                    val typeParameter = simpleProp.typeParameterNames?.firstOrNull()
                        ?: throw IllegalStateException("$propertyName is Set but does not contain information about the type parameter")
                    val oProperty =
                        createEmbeddedSetPropertyIfAbsent(propertyName, getOType(typeParameter))

                    /*
                    * If the value is not defined, the property returns true.
                    * It is handled on the DNQ entities level.
                    * But, we still apply the required state just in case.
                    * */
                    oProperty.setRequirement(required)

                    /*
                    * When creating an index on an EMBEDDEDSET field, OrientDB does not create an index for the field itself.
                    * Instead, it creates an index for each individual item in the set.
                    * This is done to enable quick searches for individual elements within the set.
                    *
                    * The same behaviour as the original behaviour of set properties in DNQ.
                    * */
                    val index = makeDeferredIndexForEmbeddedSet(propertyName)
                    addIndex(index)
                } else { // primitive types
                    val oProperty =
                        createPropertyIfAbsent(propertyName, getOType(primitiveTypeName))
                    oProperty.setRequirement(required)
                    /*
                    * A property may opt out of the automatic per-property index
                    * (SimplePropertyMetaDataImpl.isAutoIndexed, `xdStringProp(indexed = false)` in DNQ).
                    *
                    * That is the only way to store an unbounded value in a plain property here: a B-tree index
                    * key may not exceed BTREE_MAX_KEY_SIZE (30% of the page size, ~2457 bytes with the default
                    * 8 KB page), and a longer value fails the write with TooBigIndexKeyException. An unindexed
                    * property stays a normal property - queries over it scan instead of probing an index.
                    * */
                    if (indexForEverySimpleProperty && simpleProp.isAutoIndexed) {
                        addIndex(simplePropertyIndex(name, propertyName))
                    }
                    if (primitiveTypeName.lowercase() == "boolean" && oProperty.defaultValue == null) {
                        oProperty.setDefaultValue("false")
                    }
                }

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
                *
                * Booleans must be initialized with "false" by default
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

            jetbrains.exodus.query.metadata.PropertyType.TEXT -> {
                val oProperty = createPropertyIfAbsent(propertyName, PropertyType.LINK)
                oProperty.setRequirement(required)
            }

            jetbrains.exodus.query.metadata.PropertyType.BLOB -> {
                val oProperty = createPropertyIfAbsent(propertyName, PropertyType.LINK)
                oProperty.setRequirement(required)
            }
        }
        appendLine()
    }

    private fun SchemaProperty.setRequirement(required: Boolean) {
        if (required) {
            append(", required")
            if (!isMandatory) {
                setMandatory(true)
            }
            setNotNullIfDifferent(true)
        } else {
            append(", optional")
            if (isMandatory) {
                setMandatory(false)
            }
        }
    }

    private fun SchemaProperty.setNotNullIfDifferent(notNull: Boolean) {
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

    /**
     * Creates a property, skipping YouTrackDB's per-property data validation when this class provably
     * holds no records (XD-1283 performance).
     *
     * `SchemaClassEmbedded.addPropertyInternal` runs two data checks per created property,
     * `checkPersistentPropertyType` (are there existing values of an incompatible type?) and
     * `fireDatabaseMigration` (rewrite the ones that need it). Both have an in-memory fast path, but
     * it is gated on `hasOnlyTransactionLocalCollections()` - the class having been created in the
     * CURRENT transaction - and NOT on the class being empty. So every property added to a class that
     * some earlier transaction committed pays a string-interpolated SELECT whose text is unique per
     * property, which therefore never hits the query-plan cache and re-materialises the immutable
     * schema each time. Measured on the pinned engine, 900 properties over 300 committed but empty
     * classes: 6372-7533 ms with the checks versus 164-174 ms with `unsafe = true`, roughly 40x.
     * (Over classes created in the same transaction the two are equal - 197 vs 235 ms - so the
     * startup pass on a fresh database already got the engine's own fast path and gains nothing here.)
     *
     * A class with no records cannot have a value of the wrong type, so the two checks have nothing
     * to find and skipping them is not a behaviour change - see [holdsNoRecords] for what "no records"
     * is proved with, and for the one residual risk. When emptiness cannot be proved, the public
     * overload runs exactly as before.
     */
    private fun SchemaClass.createPropertyChecked(
        propertyName: String,
        oType: PropertyType,
        linkedType: PropertyType? = null
    ): SchemaProperty {
        /*
         * A class whose collections are ALL provisional was created by this very transaction, so the
         * engine already takes its own in-memory fast path and there is nothing to win. Leave those
         * to it: on a fresh database - where the startup pass creates every class in the same
         * transaction as its properties - this method then behaves exactly as before, and the change
         * is confined to properties added to classes an earlier transaction committed.
         */
        val createdInThisTransaction = polymorphicCollectionIds
            .all { SchemaShared.isProvisionalCollectionId(it) }
        if (createdInThisTransaction || !holdsNoRecords()) {
            return if (linkedType == null) createProperty(propertyName, oType)
            else createProperty(propertyName, oType, linkedType)
        }
        return (this as SchemaClassInternal).createProperty(
            propertyName,
            PropertyTypeInternal.convertFromPublicType(oType),
            PropertyTypeInternal.convertFromPublicType(linkedType),
            /* unsafe = */ true
        )
    }

    /**
     * Whether this class and all its subtypes provably hold NO records - committed or written by the
     * current transaction - which is what makes YouTrackDB's per-property data validation pointless
     * (see [createPropertyChecked]).
     *
     * Deliberately does NOT use `SchemaClassInternal.count(session, true)`: that route goes through
     * the immutable schema snapshot, which every schema write in the transaction invalidates, so
     * asking it once per class inside a DDL transaction would rebuild the whole snapshot per class -
     * paying exactly the cost this is meant to avoid. Instead both halves are read directly:
     * - committed records: the storage's per-collection counters, for the non-provisional collection
     *   ids only (a provisional id, `<= -2`, belongs to a class created in this transaction and has no
     *   storage collection to count);
     * - uncommitted records: the transaction's own per-collection walk, the same one the engine's fast
     *   path uses, with the upper bound of 0 that restricts it to this transaction's records.
     *
     * Conservative in every direction: any doubt - a missing transaction, an unexpected failure -
     * answers false and the caller keeps the validated path. Records deleted but not yet committed
     * still count as records, which can only cost performance, never correctness. Only ever asked
     * about a class with at least one committed collection, see [createPropertyChecked].
     *
     * Residual risk, accepted: a concurrent session could commit a record into the class between this
     * check and the property creation. For that to matter the record would have to carry an
     * undeclared value under the very property name being created with an incompatible type - DNQ
     * never writes undeclared values - and the SELECT the engine would have run is itself a snapshot
     * read with the same blind spot.
     */
    private fun SchemaClass.holdsNoRecords(): Boolean = recordlessClasses.getOrPut(name) {
        try {
            val collectionIds = polymorphicCollectionIds
            val committedIds = collectionIds.filterNot { SchemaShared.isProvisionalCollectionId(it) }
            val noCommittedRecords = committedIds.isEmpty() ||
                oSession.countCollectionElements(committedIds.toIntArray(), false) == 0L
            noCommittedRecords && collectionIds.none { hasTransactionLocalRecords(it) }
        } catch (e: Throwable) {
            log.debug(e) { "Could not establish whether $name holds records, keeping the validated property-creation path" }
            false
        }
    }

    private fun hasTransactionLocalRecords(collectionId: Int): Boolean {
        val transaction = oSession.transactionInternal as? FrontendTransactionImpl
            ?: return true // no transaction to walk: assume the worst and validate
        return transaction.getNextRidInCollection(RecordId(collectionId, Long.MIN_VALUE), 0) != null
    }

    private fun SchemaClass.createPropertyIfAbsent(
        propertyName: String,
        oType: PropertyType
    ): SchemaProperty {
        append(", type is $oType")
        val oProperty = if (existsProperty(propertyName)) {
            append(", already created")
            getProperty(propertyName)
        } else {
            append(", created")
            // concurrent-creation race tolerance (XD-1283) - see createEdgeClassIfAbsent
            try {
                createPropertyChecked(propertyName, oType)
            } catch (e: SchemaException) {
                if (existsProperty(propertyName)) getProperty(propertyName) else throw e
            }
        }
        if (oType == PropertyType.STRING) {
            if (oProperty.collate.name == CaseInsensitiveCollate.NAME) {
                append(", case-insensitive collate already set")
            } else {
                oProperty.setCollate(CaseInsensitiveCollate.NAME)
                append(", set case-insensitive collate")
            }
        }
        require(oProperty.type == oType) { "$propertyName type is ${oProperty.type} but $oType was expected instead. Types migration is not supported." }
        return oProperty
    }

    /*
    * linkedClass is nullable because sometimes we do not set it.
    *
    * We do not set linkedClass for direct link in-properties
    * because there can be several links with the same name.
    * Consider the following example:
    * type2 -[link1]-> type1
    * type3 -[link1]-> type1
    *
    * What linkedClass should be for type1.directInProperty?
    *
    * But we still can set linkedClassType for direct link out-properties.
    * */
    private fun SchemaClass.createLinkPropertyIfAbsent(propertyName: String): SchemaProperty {
        val oProperty = if (existsProperty(propertyName)) {
            append(", already created")
            getProperty(propertyName)
        } else {
            append(", created")
            // concurrent-creation race tolerance (XD-1283) - see createEdgeClassIfAbsent
            try {
                createPropertyChecked(propertyName, PropertyType.LINKBAG)
            } catch (e: SchemaException) {
                if (existsProperty(propertyName)) getProperty(propertyName) else throw e
            }
        }
        require(oProperty.type == PropertyType.LINKBAG) {
            "$propertyName type is ${oProperty.type} but ${PropertyType.LINKBAG} was expected instead. Types migration is not supported."
        }
        return oProperty
    }

    private fun SchemaClass.createEmbeddedSetPropertyIfAbsent(
        propertyName: String,
        oType: PropertyType
    ): SchemaProperty {
        append(", type of the set is $oType")
        val oProperty = if (existsProperty(propertyName)) {
            append(", already created")
            getProperty(propertyName)
        } else {
            append(", created")
            // concurrent-creation race tolerance (XD-1283) - see createEdgeClassIfAbsent
            try {
                createPropertyChecked(propertyName, PropertyType.EMBEDDEDSET, linkedType = oType)
            } catch (e: SchemaException) {
                if (existsProperty(propertyName)) getProperty(propertyName) else throw e
            }
        }
        if (oType == PropertyType.STRING) {
            if (oProperty.collate.name == CaseInsensitiveCollate.NAME) {
                append(", case-insensitive collate already set")
            } else {
                oProperty.setCollate(CaseInsensitiveCollate.NAME)
                append(", set case-insensitive collate")
            }
        }
        require(oProperty.type == PropertyType.EMBEDDEDSET) { "$propertyName type is ${oProperty.type} but ${PropertyType.EMBEDDEDSET} was expected instead. Types migration is not supported." }
        require(oProperty.linkedType == oType) { "$propertyName type of the set is ${oProperty.linkedType} but $oType was expected instead. Types migration is not supported." }
        return oProperty
    }

    private fun getOType(jvmTypeName: String): PropertyType {
        return when (jvmTypeName.lowercase()) {
            "boolean" -> PropertyType.BOOLEAN
            "string" -> PropertyType.STRING

            "byte" -> PropertyType.BYTE
            "short" -> PropertyType.SHORT
            "int",
            "integer" -> PropertyType.INTEGER

            "long" -> PropertyType.LONG

            "float" -> PropertyType.FLOAT
            "double" -> PropertyType.DOUBLE

            "datetime" -> PropertyType.LONG

            else -> throw IllegalArgumentException("$jvmTypeName is not supported. Feel free to support it.")
        }
    }
}
