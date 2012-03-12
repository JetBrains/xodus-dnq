package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.*;
import jetbrains.exodus.database.impl.iterate.EntityIteratorWithPropId;
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
