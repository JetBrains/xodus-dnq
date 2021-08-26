/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.exceptions.SimplePropertyValidationException
import jetbrains.exodus.query.metadata.PropertyMetaData

/**
 * General constraint for simple property
 */
abstract class PropertyConstraint<in T> {

    open fun check(entity: TransientEntity, propertyMetaData: PropertyMetaData, value: T): SimplePropertyValidationException? {
        if (isValid(value)) return null

        val propertyName = propertyMetaData.name
        return SimplePropertyValidationException(getExceptionMessage(propertyName, value), getDisplayMessage(propertyName, value), entity, propertyName)
    }

    abstract fun isValid(value: T): Boolean

    abstract fun getExceptionMessage(propertyName: String, propertyValue: T): String

    open fun getDisplayMessage(propertyName: String, propertyValue: T) =
            getExceptionMessage(propertyName, propertyValue)
}
