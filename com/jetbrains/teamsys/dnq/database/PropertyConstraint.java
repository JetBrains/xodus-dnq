package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.entitystore.metadata.PropertyMetaData;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.exceptions.SimplePropertyValidationException;

/**
 * General constraint for simple property
 */
public abstract class PropertyConstraint<T> {

    public SimplePropertyValidationException check(TransientEntity e, PropertyMetaData pmd, T value) {
        SimplePropertyValidationException exception = null;
        if (!isValid(value)) {
            String propertyName = pmd.getName();
            exception = new SimplePropertyValidationException(getExceptionMessage(propertyName, value), getDisplayMessage(propertyName, value), e, propertyName);
        }
        return exception;
    }

    protected abstract boolean isValid(T value);

    protected abstract String getExceptionMessage(String propertyName, T propertyValue);

    protected String getDisplayMessage(String propertyName, T propertyValue) {
        return getExceptionMessage(propertyName, propertyValue);
    }
}
