/**
 * Copyright 2006 - 2022 JetBrains s.r.o.
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
package kotlinx.dnq

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import kotlin.concurrent.thread

fun <T> TransientEntityStore.runTranAsyncAndJoin(readonly: Boolean = false, body: (TransientStoreSession) -> T): T {
    var result: T? = null
    val thread = thread(start = true) {
        result = transactional(readonly = readonly, block = body)
    }
    thread.join()
    @Suppress("UNCHECKED_CAST")
    return result as T
}
