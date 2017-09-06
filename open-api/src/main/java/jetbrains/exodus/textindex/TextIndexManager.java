/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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
package jetbrains.exodus.textindex;

import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityId;
import jetbrains.exodus.entitystore.EntityIterable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public interface TextIndexManager {

    void init();

    void close();

    void clearIndex();

    void queueUnindexedDocuments();

    void indexDocument(@NotNull final EntityId id, boolean forceReindexing);

    void deleteDocument(@NotNull final EntityId id);

    @NotNull
    EntityIterable searchFor(@Nullable final String queryString);

    @NotNull
    EntityIterable searchFor(@Nullable final String queryString,
                             @Nullable final String entityType);

    @NotNull
    EntityIterable searchFor(@Nullable final String queryString,
                             @Nullable final String entityType,
                             @NotNull final String field);

    boolean matchesQuery(@NotNull final Entity entity,
                         @NotNull final String queryString);

    boolean matchesQuery(@NotNull final Entity entity,
                         @NotNull final String queryString,
                         @NotNull final String field);

    EntityIterable findSimilar(@NotNull final Entity entity);

    void setSimilarityIgnoredField(@NotNull final String entityType, @NotNull final String field);

    long totalDocs();

    int pendingDocs();

    void waitForPendingDocs();

    void suspendIndexing();

    void resumeIndexing();

    boolean isSuspended();

    void addListener(TextIndexListener listener);

    void removeListener(TextIndexListener listener);

    @NotNull
    List<int[]> getOffsets(@NotNull final String text, @NotNull final String query);

    boolean getUseRussianStemmer();

    void setUseRussianStemmer(boolean stemming);

    boolean getUseGermanStemmer();

    void setUseGermanStemmer(boolean stemming);

    boolean getUseFrenchStemmer();

    void setUseFrenchStemmer(boolean stemming);

    boolean getUseSpanishStemmer();

    void setUseSpanishStemmer(boolean stemming);

    boolean getUsePortugueseStemmer();

    void setUsePortugueseStemmer(boolean stemming);

    boolean getUseItalianStemmer();

    void setUseItalianStemmer(boolean stemming);

    boolean getUseDutchStemmer();

    void setUseDutchStemmer(boolean stemming);

    boolean getUseFinnishStemmer();

    void setUseFinishStemmer(boolean stemming);

    boolean getUseSwedishStemmer();

    void setUseSwedishStemmer(boolean stemming);

    boolean getUseNorwegianStemmer();

    void setUseNorwegianStemmer(boolean stemming);

    boolean getUseDanishStemmer();

    void setUseDanishStemmer(boolean stemming);

    boolean getUsePolishStemmer();

    void setUsePolishStemmer(boolean stemming);

    boolean getUseCzechStemmer();

    void setUseCzechStemmer(boolean stemming);

    boolean getUseGreekStemmer();

    void setUseGreekStemmer(boolean stemming);

    boolean getUseLatvianStemmer();

    void setUseLatvianStemmer(boolean stemming);

    boolean getUseArabicStemmer();

    void setUseArabicStemmer(boolean stemming);

    boolean getUseTurkishStemmer();

    void setUseTurkishStemmer(boolean stemming);

    boolean getUseChineseStemmer();

    void setUseChineseStemmer(boolean stemming);

    boolean getUseJapaneseStemmer();

    void setUseJapaneseStemmer(boolean stemming);

    boolean getUseKoreanStemmer();

    void setUseKoreanStemmer(boolean stemming);

    long getStoreSize();
}
