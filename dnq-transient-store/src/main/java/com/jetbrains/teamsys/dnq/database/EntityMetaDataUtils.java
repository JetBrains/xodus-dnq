/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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

import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator;
import jetbrains.exodus.database.LinkChange;
import jetbrains.exodus.database.TransientChangesTracker;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.query.metadata.EntityMetaData;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

public class EntityMetaDataUtils {

    @NotNull
    static Set<String> getRequiredIfProperties(EntityMetaData emd, Entity e) {
        Set<String> result = new HashSetDecorator<String>();
        for (String property : emd.getRequiredIfProperties(e)) {
            if (TransientStoreUtil.getPersistentClassInstance(e).isPropertyRequired(property, e)) {
                result.add(property);
            }
        }
        return result;
    }

    @NotNull
    static Map<String, Iterable<PropertyConstraint>> getPropertyConstraints(Entity e) {
        BasePersistentClassImpl persistentClass = TransientStoreUtil.getPersistentClassInstance(e);
        return persistentClass.getPropertyConstraints();
    }

    static boolean hasParent(@NotNull EntityMetaData emd, @NotNull TransientEntity e, @NotNull TransientChangesTracker tracker) {
        final Set<String> aggregationChildEnds = emd.getAggregationChildEnds();
        if (e.isNew() || parentChanged(aggregationChildEnds, tracker.getChangedLinksDetailed(e))) {
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
