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
import jetbrains.exodus.entitystore.youtrackdb.withTx as sessionWithTx

/**
 * The canonical implementation lives in `jetbrains.exodus.entitystore.youtrackdb.withTx`
 * (dnq-entity-store) so that the schema-buddy layer can use it too (XD-1283); this delegate
 * is kept for existing callers of the `jetbrains.exodus.query.metadata` package.
 */
fun <R> DatabaseSessionEmbedded.withTx(block: (DatabaseSessionEmbedded) -> R): R =
    sessionWithTx(block)
