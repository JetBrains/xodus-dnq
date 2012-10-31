package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator;
import jetbrains.exodus.database.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

public class EntityMetaDataUtils {

    @NotNull
    static Set<String> getRequiredIfProperties(EntityMetaData emd, Entity e) {
        Set<String> result = new HashSetDecorator<String>();
        for (String property : emd.getRequiredIfProperties(e)) {
            if (TransientStoreUtil.getPersistentClassInstance(e, emd).isPropertyRequired(property, e)) {
                result.add(property);
            }
        }
        return result;
    }

    @Nullable
    static Map<String, Iterable<PropertyConstraint>> getPropertyConstraints(EntityMetaData emd, Entity e) {
        BasePersistentClassImpl persistentClass = TransientStoreUtil.getPersistentClassInstance(e, emd);
        return persistentClass.getPropertyConstraints();
    }

    static boolean changesReflectHistory(EntityMetaData emd, TransientEntity e, TransientChangesTracker tracker) {
        Map<String, PropertyChange> changedProperties = tracker.getChangedPropertiesDetailed(e);
        if (changedProperties != null) {
            for (String field : changedProperties.keySet()) {
                if (!emd.isHistoryIgnored(field)) {
                    return true;
                }
            }
        }

        Map<String, LinkChange> changedLinks = tracker.getChangedLinksDetailed(e);
        if (changedLinks != null) {
            for (String field : changedLinks.keySet()) {
                if (!emd.isHistoryIgnored(field)) {
                    return true;
                }
            }
        }

        return false;
    }

    static boolean hasParent(@NotNull EntityMetaData emd, @NotNull TransientEntity e, @NotNull TransientChangesTracker tracker) {
        final Set<String> aggregationChildEnds = emd.getAggregationChildEnds();
        if (e.isNewOrTemporary() || parentChanged(aggregationChildEnds, tracker.getChangedLinksDetailed(e))) {
            for (String childEnd : aggregationChildEnds) {
                if (AssociationSemantics.getToOne(e, childEnd) != null) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean parentChanged(Set<String> aggregationChildEnds, Map<String, LinkChange> changedLinks) {
        if (changedLinks == null) {
            return false;
        }
        for (String childEnd : aggregationChildEnds) {
            if (changedLinks.containsKey(childEnd)) {
                return true;
            }
        }
        return false;
    }
}
