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
package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.query.metadata.PropertyMetaData;
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
