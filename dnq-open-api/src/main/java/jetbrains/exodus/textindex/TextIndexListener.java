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
package jetbrains.exodus.textindex;

import jetbrains.exodus.entitystore.EntityId;
import org.jetbrains.annotations.NotNull;

public interface TextIndexListener {

    /**
     * Fired when the document that corresponds to the entity with specified id is been deleted from the full text index.
     *
     * @param id entity id of the document deleted from the index
     */
    void documentDeleted(@NotNull final EntityId id);

    /**
     * Fired when the document that corresponds to the entity with specified id is been added to the full text index
     * with specified entire document text.
     * @param id entity id of the document added to the index
     * @param docText text of the document (it's a value of the `entire_doc` field)
     */
    void documentAdded(@NotNull final EntityId id, @NotNull final String docText);

    /**
     * Fired when the text is cleared
     */
    void indexCleared();
}
