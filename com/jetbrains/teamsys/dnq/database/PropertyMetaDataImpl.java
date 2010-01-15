package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.PropertyMetaData;
import com.jetbrains.teamsys.database.PropertyType;
import org.jetbrains.annotations.NotNull;

/**
 */
public class PropertyMetaDataImpl implements PropertyMetaData {

    private String name;
    private PropertyType type;

    public void setName(String name) {
        this.name = name;
    }

    public void setType(PropertyType type) {
        this.type = type;
    }

    public PropertyMetaDataImpl() {
    }

    @NotNull
    public String getName() {
        return name;
    }

    @NotNull
    public PropertyType getType() {
        return type;
    }
}
