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

import jetbrains.exodus.database.LinkChange;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityId;
import jetbrains.exodus.entitystore.EntityIterable;
import jetbrains.exodus.entitystore.EntityIterator;
import jetbrains.exodus.entitystore.iterate.EntityIteratorWithPropId;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

class AddedOrRemovedLinksFromSetTransientEntityIterable extends TransientEntityIterable {

    private final boolean removed;
    private final Set<String> linkNames;
    private final Map<String, LinkChange> changesLinks;

    AddedOrRemovedLinksFromSetTransientEntityIterable(@NotNull Set<TransientEntity> values, boolean removed,
                                                      @NotNull Set<String> linkNames,
                                                      @NotNull Map<String, LinkChange> changesLinks) {
        super(values);
        this.removed = removed;
        this.linkNames = linkNames;
        this.changesLinks = changesLinks;
    }

    @Override
    public EntityIterator iterator() {
        final Iterator<String> it = linkNames.iterator();
        return new EntityIteratorWithPropId() {
            private String currentLinkName;
            private Iterator<TransientEntity> currentItr;

            public String currentLinkName() {
                return currentLinkName;
            }

            public boolean hasNext() {
                if (currentItr != null && currentItr.hasNext()) {
                    return true;
                }
                while (it.hasNext()) {
                    final String linkName = it.next();
                    final LinkChange linkChange = changesLinks.get(linkName);
                    if (linkChange != null) {
                        final Set<TransientEntity> current = removed ?
                                linkChange.getRemovedEntities() :
                                linkChange.getAddedEntities();
                        if (current != null) {
                            final Iterator<TransientEntity> itr = current.iterator();
                            if (itr.hasNext()) {
                                currentLinkName = linkName;
                                currentItr = itr;
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            public Entity next() {
                if (hasNext()) {
                    return currentItr.next();
                }
                return null;
            }

            public EntityId nextId() {
                return next().getId();
            }

            public boolean skip(int number) {
                while (number > 0) {
                    if (hasNext()) {
                        next();
                        --number;
                    } else {
                        return false;
                    }
                }
                return true;
            }

            public boolean shouldBeDisposed() {
                return false;
            }

            public boolean dispose() {
                throw new UnsupportedOperationException("Transient iterator doesn't support disposing.");
            }

            public void remove() {
                throw new UnsupportedOperationException("Remove from iterator is not supported by transient iterator");
            }
        };
    }

    @Override
    public long size() {
        return values.size();
    }

    @Override
    public long count() {
        return values.size();
    }

    static final EntityIterable get(@NotNull final Map<String, LinkChange> changesLinks,
                                    @NotNull final Set<String> linkNames,
                                    final boolean removed) {
        if (changesLinks == null) {
            return UniversalEmptyEntityIterable.INSTANCE;
        }
        final Set<TransientEntity> result = new HashSet<TransientEntity>();
        if (removed) {
            for (final String linkName : linkNames) {
                final LinkChange linkChange = changesLinks.get(linkName);
                final Set<TransientEntity> removedEntities;
                if (linkChange != null && (removedEntities = linkChange.getRemovedEntities()) != null) {
                    result.addAll(removedEntities);
                }
            }
        } else {
            for (final String linkName : linkNames) {
                final LinkChange linkChange = changesLinks.get(linkName);
                final Set<TransientEntity> addedEntities;
                if (linkChange != null && (addedEntities = linkChange.getAddedEntities()) != null) {
                    result.addAll(addedEntities);
                }
            }
        }
        if (result.isEmpty()) {
            return UniversalEmptyEntityIterable.INSTANCE;
        }
        return new AddedOrRemovedLinksFromSetTransientEntityIterable(result, removed, linkNames, changesLinks);
    }
}
