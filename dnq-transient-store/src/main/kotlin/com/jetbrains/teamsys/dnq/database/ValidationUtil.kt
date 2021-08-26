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

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.metadata.*
import mu.NamedKLogging

object ValidationUtil {
    private val logger = NamedKLogging(ValidationUtil::class.java.name).logger

    @JvmStatic
    fun validateEntity(entity: Entity, modelMetaData: ModelMetaData) {
        // 1. validate associations
        validateAssociations(entity, modelMetaData)

        // 2. validate required properties
        validateRequiredProperties(entity, modelMetaData)
    }


    // Validate associations
    @JvmStatic
    internal fun validateAssociations(entity: Entity, modelMetaData: ModelMetaData) {
        val entityMetaData = modelMetaData.getEntityMetaData(entity.type)
        entityMetaData?.associationEndsMetaData?.forEach { associationEndMetaData ->
            logger.trace { "Validate cardinality [${entity.type}.${associationEndMetaData.name}]. Required is [${associationEndMetaData.cardinality.getName()}]" }
            if (!checkCardinality(entity, associationEndMetaData)) {
                logger.error { "Validation: Cardinality violation for [$entity.${associationEndMetaData.name}]. Required cardinality is [${associationEndMetaData.cardinality.getName()}]" }
            }
        }
    }

    @JvmStatic
    internal fun checkCardinality(e: Entity, md: AssociationEndMetaData): Boolean {
        return checkCardinality(e, md.cardinality, md.name)
    }

    @JvmStatic
    internal fun checkCardinality(entity: Entity, cardinality: AssociationEndCardinality, associationName: String): Boolean {
        var size = 0
        val it = entity.getLinks(associationName).iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e != null) {
                ++size
            } else {
                logger.error { "Validation: Null entity in the [$entity.$associationName]" }
            }
        }

        return when (cardinality) {
            AssociationEndCardinality._0_1 -> size <= 1
            AssociationEndCardinality._0_n -> true
            AssociationEndCardinality._1 -> size == 1
            AssociationEndCardinality._1_n -> size >= 1
        }
    }


    // Validate entity properties.

    @JvmStatic
    internal fun validateRequiredProperties(entity: Entity, modelMetaData: ModelMetaData) {
        val entityMetaData = modelMetaData.getEntityMetaData(entity.type)

        if (entityMetaData != null) {
            entityMetaData.requiredProperties
                    .forEach { checkProperty(entity, entityMetaData, it) }

            EntityMetaDataUtils.getRequiredIfProperties(entityMetaData, entity)
                    .forEach { checkProperty(entity, entityMetaData, it) }
        }
    }

    @JvmStatic
    private fun checkProperty(e: Entity, emd: EntityMetaData, name: String) {
        val pmd = emd.getPropertyMetaData(name)
        val type = if (pmd != null) {
            pmd.type
        } else {
            logger.warn { "Cannot determine property type. Try to get property value as if it of primitive type." }
            PropertyType.PRIMITIVE
        }
        val isEmpty = when (type) {
            PropertyType.PRIMITIVE -> isEmptyPrimitiveProperty(e.getProperty(name))
            PropertyType.BLOB -> e.getBlob(name) == null
            PropertyType.TEXT -> isEmptyPrimitiveProperty(e.getBlobString(name))
            else -> throw IllegalArgumentException("Unknown property type: " + name)
        }
        if (isEmpty) {
            logger.error { "Validation: Property [$e.$name] is empty." }
        }
    }

    @JvmStatic
    private fun isEmptyPrimitiveProperty(propertyValue: Comparable<*>?): Boolean {
        return propertyValue == null || propertyValue == ""
    }
}
