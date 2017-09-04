package jetbrains.exodus.textindex;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface TextIndexMetaData {

    @NotNull
    String[] getIndexedEntityTypes();

    @Nullable
    TextIndexEntityMetaData getEntityMetaData(@NotNull String entityType);

    RemoveWikiFunction getRemoveWikiFunction();

    void setRemoveWikiFunction(RemoveWikiFunction rwf);

    String getVersionLabel();

    public interface RemoveWikiFunction {

        String removeWiki(final String source);
    }
}
