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

import YTDBDatabaseProviderFactory
import com.jetbrains.youtrackdb.api.DatabaseType
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseParams
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseProvider
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.LOCAL_ENTITY_ID_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.withTx
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The data validation YouTrackDB runs when a property is created, and DNQ's decision to skip it for a
 * class that provably holds no records (XD-1283 performance - see
 * `YouTrackDbSchemaInitializer.createPropertyChecked` / `holdsNoRecords`).
 *
 * Skipping must be invisible: an empty class has nothing to validate, so the outcome is identical and
 * only the cost differs. What these tests pin is the other half - that the validated path is still
 * taken whenever records exist, committed or written by the very transaction that adds the property,
 * because that is the half a wrong emptiness check would silently break.
 */
class SchemaPropertyValidationTest {

    @Rule
    @JvmField
    val youTrackDb = InMemoryYouTrackDB(initializeIssueSchema = false)

    /**
     * Writes one record of [className] carrying [propertyName] with the given value. The value is
     * UNDECLARED at this point, which is precisely the situation YouTrackDB's per-property validation
     * exists for. Uses the raw session rather than the DNQ store, because the record only has to
     * exist - no entity id, no model registration.
     */
    /** A second provider over the rule's database with [transactionalIndexCreation] pinned. */
    private fun providerWith(transactionalIndexCreation: Boolean): YTDBDatabaseProvider {
        val params = YTDBDatabaseParams.builder()
            .withDatabaseType(DatabaseType.MEMORY)
            .withDatabasePath(youTrackDb.params.databasePath)
            .withAppUser(youTrackDb.username, youTrackDb.password)
            .withDatabaseName(youTrackDb.dbName)
            .withCloseDatabaseInDbProvider(false)
            .withTransactionalIndexCreation(transactionalIndexCreation)
            .build()
        return YTDBDatabaseProviderFactory.createProvider(params, youTrackDb.database)
    }

    private var nextLocalEntityId = 1L

    private fun writeRecord(className: String, propertyName: String, value: Any) {
        youTrackDb.provider.withSession { session ->
            session.withTx {
                // the record is tracked by the transaction; no explicit save exists on this API.
                // localEntityId is mandatory in a DNQ schema, so it has to be set by hand here.
                val vertex = it.newVertex(className)
                vertex.setProperty(LOCAL_ENTITY_ID_PROPERTY_NAME, nextLocalEntityId++)
                vertex.setProperty(propertyName, value)
            }
        }
    }

    @Test
    fun `properties are created on a committed empty class`() {
        // The classes are committed FIRST, so YouTrackDB's own transaction-local fast path does not
        // apply on the second pass and DNQ's emptiness check is what decides.
        youTrackDb.provider.withSession { session ->
            session.applySchemaInTx(model { entity("type1"); entity("type2") })
        }

        youTrackDb.provider.withSession { session ->
            val result = session.applySchemaInTx(
                model {
                    entity("type1") {
                        property("prop1", "int")
                        setProperty("setProp", "string")
                    }
                    entity("type2")
                    association("type1", "ass1", "type2", AssociationEndCardinality._0_n)
                }
            )
            // the initializer defers indices; the association assertion below checks the edge index
            session.applyIndicesInTx(result.indices)
        }

        youTrackDb.provider.withSession { session ->
            val type1 = session.schema.getClass("type1")!!
            assertEquals(PropertyType.INTEGER, type1.getProperty("prop1")!!.type)
            assertEquals(PropertyType.EMBEDDEDSET, type1.getProperty("setProp")!!.type)
            session.assertAssociationExists("type1", "type2", "ass1", AssociationEndCardinality._0_n)
        }
    }

    @Test
    fun `a property created on an empty class accepts data afterwards`() {
        oModel(youTrackDb.provider) { entity("type1") }.prepare()
        // a second model over the now-committed class: this is the skipping path
        oModel(youTrackDb.provider) { entity("type1") { property("prop1", "int") } }.prepare()

        // the property really is a usable INTEGER property, not a degraded one
        writeRecord("type1", "prop1", 42)
        youTrackDb.provider.withSession { session ->
            assertEquals(PropertyType.INTEGER, session.schema.getClass("type1")!!.getProperty("prop1")!!.type)
        }
    }

    @Test
    fun `an incompatible value committed earlier still fails the property creation`() {
        // A record carrying an UNDECLARED value of the wrong type is exactly what
        // checkPersistentPropertyType exists to catch. The class holds a record, so the emptiness memo
        // must answer false and the validated path must run.
        oModel(youTrackDb.provider) { entity("type1") }.prepare()
        writeRecord("type1", "prop1", "not an int")

        assertFailsWith<Exception> {
            oModel(youTrackDb.provider) { entity("type1") { property("prop1", "int") } }.prepare()
        }

        youTrackDb.provider.withSession { session ->
            val type1 = session.schema.getClass("type1")!!
            assertTrue(
                !type1.existsProperty("prop1") || type1.getProperty("prop1")!!.type != PropertyType.INTEGER,
                "the incompatible property must not have been declared as INTEGER"
            )
        }
    }

    @Test
    fun `an incompatible value in a SUBCLASS still fails the property creation`() {
        // The emptiness check must be polymorphic: the record lives in the subclass while the property
        // is declared on the super class, so a non-polymorphic check would call the super class empty
        // and skip the validation.
        oModel(youTrackDb.provider) {
            entity("base")
            entity("sub", "base")
        }.prepare()
        writeRecord("sub", "prop1", "not an int")

        assertFailsWith<Exception> {
            oModel(youTrackDb.provider) {
                entity("base") { property("prop1", "int") }
                entity("sub", "base")
            }.prepare()
        }
    }

    @Test
    fun `an empty class next to a populated one is unaffected by it`() {
        // The memo is per class: the populated class must not make the empty one slow, and the empty
        // one must not make the populated one skip its check. Both declarations are legal, so the
        // whole application must succeed.
        // pinned to the legacy non-transactional index path: declaring a simple property on a
        // POPULATED class also creates its index, which upstream YTDB-1064 rejects in-transaction
        // (covered by its own test in OModelMetaDataTest) - not what this test is about
        val nonTxProvider = providerWith(transactionalIndexCreation = false)

        oModel(nonTxProvider) {
            entity("populated")
            entity("empty")
        }.prepare()
        writeRecord("populated", "prop1", "a string")

        oModel(nonTxProvider) {
            entity("populated") { property("prop1", "string") }
            entity("empty") { property("prop1", "int") }
        }.prepare()

        youTrackDb.provider.withSession { session ->
            assertEquals(PropertyType.STRING, session.schema.getClass("populated")!!.getProperty("prop1")!!.type)
            assertEquals(PropertyType.INTEGER, session.schema.getClass("empty")!!.getProperty("prop1")!!.type)
        }
    }

    @Test
    fun `a runtime association over populated classes still works`() {
        // The LINKBAG properties of a runtime association add are the hot case for the skip; over
        // POPULATED classes they must keep taking the validated path and still succeed.
        val model = oModel(youTrackDb.provider) {
            entity("type1")
            entity("type2")
        }
        model.prepare()
        youTrackDb.provider.withSession { session ->
            session.withTx {
                it.newVertex("type1").setProperty(LOCAL_ENTITY_ID_PROPERTY_NAME, nextLocalEntityId++)
                it.newVertex("type2").setProperty(LOCAL_ENTITY_ID_PROPERTY_NAME, nextLocalEntityId++)
            }
        }

        model.association("type1", "ass1", "type2", AssociationEndCardinality._0_n)

        youTrackDb.provider.withSession { session ->
            session.assertAssociationExists("type1", "type2", "ass1", AssociationEndCardinality._0_n)
        }
    }

}
