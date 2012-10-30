package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.PropertyMetaData;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.exceptions.SimplePropertyValidationException;

/**
 * General constraint for simple property
 */
public abstract class PropertyConstraint<T> {

    public abstract SimplePropertyValidationException check(TransientEntity e, PropertyMetaData pmd, T value);

    protected SimplePropertyValidationException error(String messageFormat, String displayMessageFormat, TransientEntity e, PropertyMetaData pmd, T value) {
        String message = String.format(messageFormat, pmd.getName(), value);
        String displayMessage = String.format(displayMessageFormat, pmd.getName(), value);
        return new SimplePropertyValidationException(message, displayMessage, e, pmd.getName());
    }
}
