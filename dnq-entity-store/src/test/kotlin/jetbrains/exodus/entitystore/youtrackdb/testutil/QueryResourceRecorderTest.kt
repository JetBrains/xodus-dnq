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
package jetbrains.exodus.entitystore.youtrackdb.testutil

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration
import com.jetbrains.youtrackdb.internal.core.query.ResultSet
import io.mockk.isMockKMock
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QueryResourceRecorderTest {
    @Rule
    @JvmField
    val youTrackDb = InMemoryYouTrackDB(initializeIssueSchema = false, autoInitializeSchemaBuddy = false)

    @Test
    fun `captures a real open query and repeated attachment preserves interception`() {
        youTrackDb.withTxSession { session ->
            val before = session.activeQueries.size
            QueryResourceRecorder().use { recorder ->
                recorder.attach(session)
                recorder.attach(session)
                val result = session.activeTransaction.query("SELECT 1 AS value")
                assertSame(result, recorder.resources.single())
                assertEquals(before + 1, session.activeQueries.size)
                result.close()
                assertEquals(before, session.activeQueries.size)
            }
            assertFalse(isMockKMock(session, objectMock = true))
            session.activeTransaction.query("SELECT 2 AS value").use { result ->
                assertEquals(before + 1, session.activeQueries.size)
                assertEquals(2, result.next().getProperty<Int>("value"))
            }
            assertEquals(before, session.activeQueries.size)
        }
    }

    @Test
    fun `failure closes live registrations unmocks the session and restores the translator`() {
        youTrackDb.withTxSession { session ->
            val flag = GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED
            val configuration = requireNotNull(session.configuration)
            val previous = configuration.getValueAsBoolean(flag)
            val before = session.activeQueries.size
            val expected = IllegalStateException("scope failure")
            lateinit var result: ResultSet
            val actual = assertFailsWith<IllegalStateException> {
                withNativeQueryRecorder(session) { recorder ->
                    assertFalse(configuration.getValueAsBoolean(flag))
                    result = session.activeTransaction.query("SELECT 1 AS value")
                    assertSame(result, recorder.resources.single())
                    assertEquals(before + 1, session.activeQueries.size)
                    throw expected
                }
            }
            assertSame(expected, actual)
            assertTrue(result.isClosed, "Recorder cleanup must close the retained result")
            assertEquals(before, session.activeQueries.size)
            assertFalse(isMockKMock(session, objectMock = true))
            assertEquals(previous, configuration.getValueAsBoolean(flag))
        }
    }
}
