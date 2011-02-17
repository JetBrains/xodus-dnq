package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.PropertyMetaData;
import com.jetbrains.teamsys.database.PropertyType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 */
public class SimplePropertyMetaDataImpl extends PropertyMetaDataImpl {

    private String primitiveTypeName;


    @Nullable
    public String getPrimitiveTypeName() {
        return primitiveTypeName;
    }

    public void setPrimitiveTypeName(String primitiveTypeName) {
        this.primitiveTypeName = primitiveTypeName;
    }
}
