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
package kotlinx.dnq.query

import kotlinx.dnq.DBTest

/**
 * Runs [verify] against the same data twice: first inside the transaction that created it
 * ("uncommitted": rows are only visible through the open transaction), then in a fresh
 * transaction after the data was committed ("committed").
 */
internal fun DBTest.verifyUncommittedAndCommitted(create: () -> Unit, verify: (phase: String) -> Unit) {
    store.transactional {
        create()
        verify("uncommitted")
    }
    store.transactional {
        verify("committed")
    }
}
