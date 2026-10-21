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

import org.junit.Test
import kotlin.test.assertEquals

class MigrationExecutionOptionsTest {
    private fun assertOptions(batch: String?, validation: String?, expectedBatch: Int, expectedValidation: Boolean) {
        val properties = mapOf("entitiesPerTransaction" to batch, "validateDataAfterMigration" to validation)
        val options = MigrationExecutionOptions(properties::get)
        assertEquals(batch, options.entitiesPerTransactionStr)
        assertEquals(validation, options.validateDataAfterMigrationStr)
        assertEquals(expectedBatch, options.entitiesPerTransaction)
        assertEquals(expectedValidation, options.validateDataAfterMigration)
    }

    @Test
    fun `absent properties use defaults`() {
        assertOptions(null, null, 100, true)
    }

    @Test
    fun `custom batch is independent of validation`() {
        assertOptions("1000", null, 1000, true)
        assertOptions("1000", "true", 1000, true)
        assertOptions("1000", "false", 1000, false)
        assertOptions("1000", "250", 1000, true)
        assertOptions(null, "250", 100, true)
    }

    @Test
    fun `absent and invalid batch use default without changing validation`() {
        for (batch in listOf(null, "", "invalid", " 250", "250 ", "2147483648", "-2147483649")) {
            assertOptions(batch, "false", 100, false)
        }
    }

    @Test
    fun `zero negative and integer bounds retain parsed values`() {
        for ((batch, expected) in listOf("0" to 0, "-7" to -7, "2147483647" to 2147483647, "-2147483648" to -2147483648)) {
            assertOptions(batch, "false", expected, false)
        }
    }

    @Test
    fun `invalid validation retains strict default without changing batch`() {
        for (validation in listOf("", "invalid", "FALSE", "TRUE", " false", "false ", "1")) {
            assertOptions("250", validation, 250, true)
        }
    }
}
