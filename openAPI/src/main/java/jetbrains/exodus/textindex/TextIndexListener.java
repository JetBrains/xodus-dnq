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
