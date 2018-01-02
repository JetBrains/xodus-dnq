/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.textindex

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterable

interface TextIndexManager {

    val isSuspended: Boolean

    var useRussianStemmer: Boolean

    var useGermanStemmer: Boolean

    var useFrenchStemmer: Boolean

    var useSpanishStemmer: Boolean

    var usePortugueseStemmer: Boolean

    var useItalianStemmer: Boolean

    var useDutchStemmer: Boolean

    val useFinnishStemmer: Boolean

    var useSwedishStemmer: Boolean

    var useNorwegianStemmer: Boolean

    var useDanishStemmer: Boolean

    var usePolishStemmer: Boolean

    var useCzechStemmer: Boolean

    var useGreekStemmer: Boolean

    var useLatvianStemmer: Boolean

    var useArabicStemmer: Boolean

    var useTurkishStemmer: Boolean

    var useChineseStemmer: Boolean

    var useJapaneseStemmer: Boolean

    var useKoreanStemmer: Boolean

    val storeSize: Long

    fun init()

    fun close()

    fun clearIndex()

    fun queueUnindexedDocuments()

    fun indexDocument(id: EntityId, forceReindexing: Boolean)

    fun deleteDocument(id: EntityId)

    fun searchFor(queryString: String?): EntityIterable

    fun searchFor(queryString: String?,
                  entityType: String?): EntityIterable

    fun searchFor(queryString: String?,
                  entityType: String?,
                  field: String): EntityIterable

    fun matchesQuery(entity: Entity,
                     queryString: String): Boolean

    fun matchesQuery(entity: Entity,
                     queryString: String,
                     field: String): Boolean

    fun findSimilar(entity: Entity): EntityIterable

    fun setSimilarityIgnoredField(entityType: String, field: String)

    fun totalDocs(): Long

    fun pendingDocs(): Int

    fun waitForPendingDocs()

    fun suspendIndexing()

    fun resumeIndexing()

    fun addListener(listener: TextIndexListener)

    fun removeListener(listener: TextIndexListener)

    fun getOffsets(text: String, query: String): List<IntArray>

    fun setUseFinishStemmer(stemming: Boolean)
}
