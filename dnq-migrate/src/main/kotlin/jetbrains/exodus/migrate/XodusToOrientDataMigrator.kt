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
package jetbrains.exodus.migrate

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import jetbrains.exodus.entitystore.youtrackdb.YTDBComparableSet
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseProvider
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityId
import jetbrains.exodus.entitystore.youtrackdb.YTDBPersistentEntityStore
import jetbrains.exodus.entitystore.youtrackdb.YTDBSchemaBuddy
import jetbrains.exodus.entitystore.youtrackdb.YTDBSchemaBuddyImpl
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import jetbrains.exodus.query.metadata.YTDBModelMetaData
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.CLASS_ID_CUSTOM_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.LOCAL_ENTITY_ID_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.asEdgeClass
import jetbrains.exodus.entitystore.youtrackdb.createSequencesIfAbsent
import jetbrains.exodus.query.metadata.withTx
import jetbrains.shaded.exodus.bindings.ComparableSet
import jetbrains.shaded.exodus.entitystore.Entity
import jetbrains.shaded.exodus.entitystore.EntityId
import jetbrains.shaded.exodus.entitystore.EntityRemovedInDatabaseException
import jetbrains.shaded.exodus.entitystore.PersistentEntityStore
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

private val log = KotlinLogging.logger { }

internal fun mappedPropertyNames(entity: Entity, mapPropertyName: (String) -> String): List<Pair<String, String>> {
    val destinations = HashSet<String>()
    val blobNames = entity.blobNames.toSet()
    return entity.propertyNames.map { source ->
        val target = mapPropertyName(source)
        require(destinations.add(target)) {
            "Ordinary property destination collision on ${entity.id}: $source -> $target"
        }
        if (target != source) {
            require(target !in blobNames && target !in YTDBVertexEntity.IGNORED_PROPERTY_NAMES &&
                    YTDBVertexEntity.IGNORED_SUFFIXES.none { target.endsWith(it) }) {
                "Renamed property destination collision on ${entity.id}: $source -> $target (blob/internal field)"
            }
        }
        source to target
    }
}

/**
 * Copies ordinary properties using [mapPropertyName], which must be pure and deterministic.
 * The default is identity. Blob, link and entity-type names and value conversion are unchanged.
 * Supply the same function to [checkDataIsSame] for target lookup and filtering.
 * Distinct ordinary names on one entity must not reach one destination. Renamed outputs must
 * not reach that entity's blob names or the store's ignored internal names/suffixes.
 * Callback exceptions and collision refusals propagate. Earlier target batches may remain,
 * so a nonfresh partial target cannot be retried. These checks are not a source-wide preflight.
 */
fun migrateDataFromXodusToOrientDb(
    xodus: PersistentEntityStore,
    orientProvider: YTDBDatabaseProvider,
    /*
    * How many entities should be copied in a single transaction
    * */
    entitiesPerTransaction: Int = 10,
    mapPropertyName: (String) -> String = { it }
): XodusToOrientMigrationStats {
    orientProvider.withSession(XodusMigrationTargetFreshness::inspect)
    val schemaBuddy = YTDBModelMetaData(
        orientProvider, YTDBSchemaBuddyImpl(orientProvider, autoInitialize = false)
    )
    YTDBPersistentEntityStore(orientProvider, "migration", schemaBuddy).use { orient ->
        return XodusToOrientDataMigrator(
            xodus, orient, orientProvider, schemaBuddy, entitiesPerTransaction,
            mapPropertyName = mapPropertyName
        ).migrate()
    }
}

data class XodusToOrientMigrationStats(
    val entityClasses: Int,
    val createEntityClassesDuration: Duration,
    val entities: Long,
    val properties: Long,
    val blobs: Long,
    val copyEntitiesPropertiesAndBlobsTransactions: Long,
    val copyEntitiesPropertiesAndBlobsDuration: Duration,
    val createEntitiesDuration: Duration,
    val copyPropertiesDuration: Duration,
    val copyBlobsDuration: Duration,
    val commitEntitiesPropertiesAndBlobsDuration: Duration,

    val edgeClasses: Int,
    val createEdgeClassesDuration: Duration,
    val processedLinks: Long,
    val copiedLinks: Long,
    val copyLinksTransactions: Long,
    val copyLinksTotalDuration: Duration,
    val copyLinksDuration: Duration,
    val commitLinksDuration: Duration,

    val xEntityIdToOEntityId: Map<EntityId, YTDBEntityId>
)

/**
 * This class is responsible for migrating data from Xodus to OrientDB.
 *
 * @param xodus The Xodus PersistentEntityStore instance.
 * @param orient The OrientDB OPersistentEntityStore instance.
 * @param entitiesPerTransaction The number of entities to be copied in a single transaction.
 */
internal class XodusToOrientDataMigrator(
    private val xodus: PersistentEntityStore,
    private val orient: YTDBPersistentEntityStore,
    private val orientProvider: YTDBDatabaseProvider,
    private val schemaBuddy: YTDBSchemaBuddy,
    /*
    * How many entities should be copied in a single transaction
    * */
    private val entitiesPerTransaction: Int = 10,
    private val printProgressAtLeastOnceIn: Int = 5_000,
    private val mapPropertyName: (String) -> String = { it }
) {
    private val xEntityIdToOEntityId = HashMap<EntityId, YTDBEntityId>()

    private var entityClassesCount = 0
    private var edgeClassesCount = 0

    private var totalEntities = 0L
    private var totalProperties = 0L
    private var totalBlobs = 0L
    private var createEntitiesDuration = Duration.Companion.ZERO
    private var copyPropertiesDuration = Duration.Companion.ZERO
    private var copyBlobsDuration = Duration.Companion.ZERO
    private var commitEntitiesDuration = Duration.Companion.ZERO

    private var totalLinksProcessed = 0L
    private var totalLinksCopied = 0L
    private var copyLinksDuration = Duration.Companion.ZERO
    private var commitLinksDuration = Duration.Companion.ZERO

    private var copyEntitiesPropertiesAndBlobsTransactions = 0L
    private var copyLinksTransactions = 0L

    fun migrate(): XodusToOrientMigrationStats {
        log.info { "Starting Xodus -> OrientDB data migration" }
        // Validate every counter destination before the first migration write, through either entry.
        val sequences = migrationSequenceState(xodus)
        val createVertexClassesDuration = measureTime {
            createVertexClassesIfAbsent()
            orientProvider.withSession { it.createSequencesIfAbsent(sequences) }
        }
        val (edgeClassesToCreate, copyPropertiesAndBlobsDuration) = measureTimedValue {
            copyPropertiesAndBlobs()
        }
        val createEdgeClassesDuration = measureTime {
            createEdgeClassesIfAbsent(edgeClassesToCreate)
        }
        edgeClassesCount = edgeClassesToCreate.size
        val copyLinksTotalDuration = measureTime {
            copyLinks()
        }
        return XodusToOrientMigrationStats(
            entityClasses = entityClassesCount,
            createEntityClassesDuration = createVertexClassesDuration,

            entities = totalEntities,
            properties = totalProperties,
            blobs = totalBlobs,
            copyEntitiesPropertiesAndBlobsTransactions = copyEntitiesPropertiesAndBlobsTransactions,
            copyEntitiesPropertiesAndBlobsDuration = copyPropertiesAndBlobsDuration,
            createEntitiesDuration = createEntitiesDuration,
            copyPropertiesDuration = copyPropertiesDuration,
            copyBlobsDuration = copyBlobsDuration,
            commitEntitiesPropertiesAndBlobsDuration = commitEntitiesDuration,

            edgeClasses = edgeClassesCount,
            createEdgeClassesDuration = createEdgeClassesDuration,

            processedLinks = totalLinksProcessed,
            copiedLinks = totalLinksCopied,
            copyLinksTransactions = copyLinksTransactions,
            copyLinksTotalDuration = copyLinksTotalDuration,
            copyLinksDuration = copyLinksDuration,
            commitLinksDuration = commitLinksDuration,

            xEntityIdToOEntityId = xEntityIdToOEntityId
        )
    }

    private fun createVertexClassesIfAbsent() {
        // make sure all the vertex classes are created in OrientDB.
        // Schema DDL is transactional in YTDB (XD-1283): the whole class-creation pass runs
        // in one explicit transaction - pure DDL only; sequence creation happens after it
        // commits, before any data is copied.
        log.info { "1. Copy entity types" }
        orientProvider.withSession { oSession ->
            xodus.withReadonlyTx { xTx ->
                val entityTypes = xTx.entityTypes.toSet()
                log.info { "${entityTypes.size} entity types to copy" }
                oSession.withTx {
                    entityTypes.forEachIndexed { i, type ->
                        log.info { "$i $type is being copied" }
                        val oClass = oSession.schema.getClass(type) ?: oSession.schema.createVertexClass(type)
                        val classId = xodus.getEntityTypeId(type)

                        oClass.setCustom(CLASS_ID_CUSTOM_PROPERTY_NAME, classId.toString())

                        // create localEntityId property if absent
                        if (oClass.getProperty(LOCAL_ENTITY_ID_PROPERTY_NAME) == null) {
                            oClass.createProperty(LOCAL_ENTITY_ID_PROPERTY_NAME, PropertyType.LONG)
                        }
                    }
                }
                entityClassesCount = entityTypes.size
            }

            log.info { "All the types have been copied" }
        }
    }

    private fun copyPropertiesAndBlobs(): Set<String> {
        log.info { "2. Copy entities, their simple properties and blobs" }
        val edgeClassesToCreate = HashSet<String>()
        xodus.withReadonlyTx { xTx ->
            orient.withCountingTx(entitiesPerTransaction) { countingTx ->
                val entityTypes = xTx.entityTypes.toSet()
                val entityTypesCount = entityTypes.size
                entityTypes.forEachIndexed { typeIdx, type ->
                    val xEntities = xTx.getAll(type)
                    val xEntitiesCount = xEntities.size()
                    log.info { "$typeIdx/${entityTypesCount} $type $xEntitiesCount entities to copy" }
                    var properties = 0
                    var blobs = 0
                    var lastProgressPrintedAt = System.currentTimeMillis()
                    xEntities.forEachIndexed { xEntityIdx, xEntity ->
                        val (oEntity, createVertexDuration) = measureTimedValue {
                            val localEntityId = xEntity.id.localId

                            countingTx.newVertex(type, localEntityId)
                        }
                        createEntitiesDuration += createVertexDuration

                        copyPropertiesDuration += measureTime {
                            for ((propName, targetName) in mappedPropertyNames(xEntity, mapPropertyName)) {
                                val propValue = xEntity.getProperty(propName)
                                val comparableValue = if (propValue is ComparableSet<*>) {
                                    YTDBComparableSet(propValue.toHashSet())
                                } else {
                                    propValue as Comparable<*>
                                }
                                oEntity.setProperty(targetName, comparableValue)
                                properties++
                            }
                        }

                        copyBlobsDuration += measureTime {
                            for (blobName in xEntity.blobNames) {
                                xEntity.getBlob(blobName)?.let { blobValue ->
                                    oEntity.setBlob(blobName, blobValue)
                                    blobs++
                                }
                            }
                        }

                        xEntityIdToOEntityId[xEntity.id] = oEntity.id
                        commitEntitiesDuration += measureTime {
                            countingTx.increment()
                        }

                        if (System.currentTimeMillis() - lastProgressPrintedAt > printProgressAtLeastOnceIn) {
                            log.info { "${typeIdx}/${entityTypes.size} $type current entity $xEntityIdx/$xEntitiesCount, properties copied: $properties, blobs copied: $blobs" }
                            lastProgressPrintedAt = System.currentTimeMillis()
                        }

                        edgeClassesToCreate.addAll(xEntity.linkNames)
                    }

                    log.info { "${typeIdx}/${entityTypes.size} $type entities copied: $xEntitiesCount, properties copied: $properties, blobs copied: $blobs" }
                    totalEntities += xEntitiesCount
                    totalProperties += properties
                    totalBlobs += blobs
                }
                countingTx.commit()
                copyEntitiesPropertiesAndBlobsTransactions = countingTx.transactionsCommited
                log.info { "Entities have been copied. Entity types: ${entityTypesCount}, entities copied: $totalEntities, properties copied: $totalProperties, blobs copied: $totalBlobs" }
            }
        }
        return edgeClassesToCreate
    }

    private fun createEdgeClassesIfAbsent(edgeClassesToCreate: Set<String>) {
        log.info { "3. Create edge classes" }
        log.info { "${edgeClassesToCreate.size} edge classes to create" }
        // Schema DDL is transactional in YTDB (XD-1283): create all edge classes in one
        // explicit transaction.
        orientProvider.withSession { oSession ->
            oSession.withTx {
                edgeClassesToCreate.forEachIndexed { i, edgeClassName ->
                    log.info { "$i $edgeClassName ${edgeClassName.asEdgeClass} is being copied" }
                    oSession.schema.getClass(edgeClassName.asEdgeClass)
                        ?: oSession.schema.createEdgeClass(edgeClassName.asEdgeClass)
                }
            }
        }
        log.info { "${edgeClassesToCreate.size} edge classes have been created" }
    }

    private fun copyLinks() {
        log.info { "4. Copy links" }
        xodus.withReadonlyTx { xTx ->
            orient.withCountingTx(entitiesPerTransaction) { countingTx ->
                val entityTypes = xTx.entityTypes.toSet()
                val entityTypesCount = entityTypes.size
                log.info { "$entityTypesCount entity types to copy links" }
                var totalEntities = 0L
                entityTypes.forEachIndexed { typeIdx, type ->
                    var linksProcessed = 0
                    var linksCopied = 0
                    val xEntities = xTx.getAll(type)
                    val xEntitiesCount = xEntities.size()
                    log.info { "$typeIdx/${entityTypesCount} $type $xEntitiesCount entities to copy" }
                    var lastProgressPrintedAt = System.currentTimeMillis()
                    xEntities.forEachIndexed { xEntityIdx, xEntity ->
                        val oEntityId = xEntityIdToOEntityId.getValue(xEntity.id)
                        val oEntity = orient.getEntity(oEntityId)

                        copyLinksDuration += measureTime {
                            for (linkName in xEntity.linkNames) {
                                val copiedLinks = HashSet<EntityId>()
                                for (xTargetEntity in xEntity.getLinks(linkName)) {
                                    linksProcessed++
                                    if (copiedLinks.contains(xTargetEntity.id)) continue

                                    try {
                                        xTx.getEntity(xTargetEntity.id)
                                        val oTargetId =
                                            xEntityIdToOEntityId.getValue(xTargetEntity.id)
                                        oEntity.addLink(linkName, oTargetId)
                                        copiedLinks.add(xTargetEntity.id)
                                        linksCopied++
                                    } catch (e: EntityRemovedInDatabaseException) {
                                        // ignore
                                    }
                                    if (System.currentTimeMillis() - lastProgressPrintedAt > printProgressAtLeastOnceIn) {
                                        log.info { "${typeIdx}/${entityTypes.size} $type current entity $xEntityIdx/$xEntitiesCount, links processed: $linksProcessed, links copied: $linksCopied" }
                                        lastProgressPrintedAt = System.currentTimeMillis()
                                    }
                                }
                                if (copiedLinks.isNotEmpty()) {
                                    val commitDuration = measureTime {
                                        countingTx.increment()
                                    }
                                    commitLinksDuration += commitDuration
                                    // we want to exclude the commit duration from the copy duration
                                    copyLinksDuration -= commitDuration
                                }
                            }
                        }
                    }
                    log.info { "${typeIdx}/${entityTypes.size} $type entities processed $xEntitiesCount, links processed: $linksProcessed, links copied: $linksCopied" }
                    totalEntities += xEntitiesCount
                    totalLinksProcessed += linksProcessed
                    totalLinksCopied += linksCopied
                }
                countingTx.commit()
                copyLinksTransactions = countingTx.transactionsCommited
                log.info { "Links have been copied. Entity types: ${entityTypes.size}, entities processed: $totalEntities, links processed: $totalLinksProcessed, links copied: $totalLinksCopied" }
            }
        }
    }
}