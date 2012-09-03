package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator;
import jetbrains.exodus.database.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.Set;

/**
 * Created by IntelliJ IDEA.
 * User: user
 * Date: 8/12/11
 * Time: 3:30 PM
 * To change this template use File | Settings | File Templates.
 */
public class ValidationUtil {

    private static final Log log = LogFactory.getLog(ConstraintsUtil.class);


    public static void validateEntity(@NotNull Entity entity,@NotNull ModelMetaData modelMetaData) {

        // 1. validate associations
        validateAssociations(entity, modelMetaData);

        // 2. validate required properties
        validateRequiredProperties(entity, modelMetaData);
    }


    // Validate associations
    static void validateAssociations(@NotNull Entity entity, @NotNull ModelMetaData modelMetaData) {
        EntityMetaData md = modelMetaData.getEntityMetaData(entity.getType());
        for (AssociationEndMetaData aemd : md.getAssociationEndsMetaData()) {
            if (log.isTraceEnabled()) {
                log.trace("Validate cardinality [" + entity.getType() + "." + aemd.getName() + "]. Required is [" + aemd.getCardinality().getName() + "]");
            }

            if (!checkCardinality(entity, aemd)) {
                cardinalityViolation(entity, aemd);
            }
        }
    }

    static boolean checkCardinality(Entity e, AssociationEndMetaData md) {
        return checkCardinality(e, md.getCardinality(), md.getName());
    }

    static boolean checkCardinality(Entity entity, AssociationEndCardinality cardinality, String associationName) {
        int size = 0;
        for (Iterator<Entity> it = entity.getLinks(associationName).iterator(); it.hasNext(); ++size) {
            Entity e = it.next();
            if (e == null) {
                fakeEntityLink(e, associationName);
                --size;
            }
        }

        switch (cardinality) {
            case _0_1:
                return size <= 1;

            case _0_n:
                return true;

            case _1:
                return size == 1;

            case _1_n:
                return size >= 1;
        }
        unknownCardinality(cardinality);
        return false;
    }


    // Validate entity properties.

    static void validateRequiredProperties(@NotNull Entity entity, @NotNull ModelMetaData mmd) {
        EntityMetaData emd = mmd.getEntityMetaData(entity.getType());

        Set<String> required = emd.getRequiredProperties();
        Set<String> requiredIf = EntityMetaDataUtils.getRequiredIfProperties(emd, entity);

        for (String property: required) {
            checkProperty(entity, emd, property);
        }
        for (String property: requiredIf) {
            checkProperty(entity, emd, property);
        }
    }

    private static void checkProperty(Entity e, EntityMetaData emd, String name) {
        final PropertyMetaData pmd = emd.getPropertyMetaData(name);
        final PropertyType type;
        if (pmd == null) {
            log.warn("Can't determine property type. Try to get property value as if it of primitive type.");
            type = PropertyType.PRIMITIVE;
        } else {
            type = pmd.getType();
        }
        checkProperty(e, name, type);
    }

    private static void checkProperty(Entity e, String name, PropertyType type) {

        switch (type) {
            case PRIMITIVE:
                if (isEmptyPrimitiveProperty(e.getProperty(name))) {
                    noProperty(e, name);
                }
                break;

            case BLOB:
                if (e.getBlob(name) == null) {
                    noProperty(e, name);
                }
                break;

            case TEXT:
                if (isEmptyPrimitiveProperty(e.getBlobString(name))) {
                    noProperty(e, name);
                }
                break;

            default:
                throw new IllegalArgumentException("Unknown property type: " + name);
        }
    }
    private static boolean isEmptyPrimitiveProperty(Comparable propertyValue) {
        return propertyValue == null || "".equals(propertyValue);
    }


    // Error log

    private static void noProperty(Entity entity, String propertyName) {
        log.error("Validation: Property [" + entity + "." + propertyName + "]" + " is empty.");
    }

    private static void unknownCardinality(AssociationEndCardinality cardinality) {
        log.error("Validation: Unknown cardinality [" + cardinality + "]");
    }

    private static void cardinalityViolation(Entity entity, AssociationEndMetaData md) {
        log.error("Validation: Cardinality violation for [" + entity + "." + md.getName() + "]. Required cardinality is [" + md.getCardinality().getName() + "]");
    }

    private static void fakeEntityLink(Entity entity, String associationName) {
        log.error("Validation: Null entity in the [" + entity + "." + associationName + "]");
    }

}
