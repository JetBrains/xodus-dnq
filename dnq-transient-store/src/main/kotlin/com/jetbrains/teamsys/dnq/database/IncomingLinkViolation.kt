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


import jetbrains.exodus.core.dataStructures.NanoSet;
import jetbrains.exodus.entitystore.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class IncomingLinkViolation {

    private static final int MAXIMUM_BAD_LINKED_ENTITIES_TO_SHOW = 10;

    @NotNull
    private String linkName;
    @NotNull
    private List<Entity> entitiesCausedViolation;
    private boolean hasMoreEntitiesCausedViolations;

    public IncomingLinkViolation(@NotNull String linkName) {
        this.linkName = linkName;
        this.entitiesCausedViolation = new ArrayList<Entity>(MAXIMUM_BAD_LINKED_ENTITIES_TO_SHOW);
        this.hasMoreEntitiesCausedViolations = false;
    }

    @NotNull
    public String getLinkName() {
        return linkName;
    }

    public boolean tryAddCause(@NotNull Entity cause) {
        if (entitiesCausedViolation.size() < MAXIMUM_BAD_LINKED_ENTITIES_TO_SHOW) {
            entitiesCausedViolation.add(cause);
            return true;
        }
        hasMoreEntitiesCausedViolations = true;
        return false;
    }

    @NotNull
    public Collection<String> getDescription() {
        // default implementation
        return createPerTypeDefaultErrorMessage(linkName + " for ");
    }

    @NotNull
    private Collection<String> createPerTypeDefaultErrorMessage(@NotNull String linkDescription) {
        StringBuilder entitiesDescriptionBuilder = new StringBuilder();
        entitiesDescriptionBuilder.append(linkDescription);
        entitiesDescriptionBuilder.append("{");
        Iterator<Entity> iterator = entitiesCausedViolation.iterator();
        while (iterator.hasNext()) {
            entitiesDescriptionBuilder.append(iterator.next().toString());
            if (iterator.hasNext()) entitiesDescriptionBuilder.append(", ");
        }
        if (hasMoreEntitiesCausedViolations) {
            entitiesDescriptionBuilder.append(" and more...}");
        } else {
            entitiesDescriptionBuilder.append("}");
        }
        return new NanoSet<String>(entitiesDescriptionBuilder.toString());
    }

    @NotNull
    final protected Collection<String> createPerInstanceErrorMessage(@NotNull MessageBuilder messageBuilder) {
        List<String> res = new ArrayList<String>();
        for (Entity entity : entitiesCausedViolation) {
            res.add(messageBuilder.build(null, entity, hasMoreEntitiesCausedViolations));
        }
        if (hasMoreEntitiesCausedViolations) {
            res.add("and more...");
        }
        return res;
    }

    @NotNull
    final protected Collection<String> createPerTypeErrorMessage(@NotNull MessageBuilder messageBuilder) {
        String description = messageBuilder.build(new Iterable<Entity>() {
            @NotNull
            public Iterator<Entity> iterator() {
                return entitiesCausedViolation.iterator();
            }
        }, null, hasMoreEntitiesCausedViolations);
        return new NanoSet<String>(description);
    }
}
