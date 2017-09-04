package jetbrains.exodus.textindex;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface TextIndexEntityMetaData {

    @NotNull
    String getEntityType();

    String[] getFieldNames();

    @Nullable
    FieldTextExtractor getFieldTextExtractor(@NotNull final String fieldName);
}
