package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.EntityIterator;
import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.EntityId;
import jetbrains.exodus.database.TransientEntity;

import java.util.Iterator;

/**
 * Date: 12.03.2007
 * Time: 15:10:33
 *
 * @author Vadim.Gurov
 */
class TransientEntityIterator implements EntityIterator {

    private Iterator<TransientEntity> iter;

    TransientEntityIterator(Iterator<TransientEntity> iterator) {
        this.iter = iterator;
    }

    public boolean hasNext() {
        return iter.hasNext();
    }

    public Entity next() {
        return iter.next();
    }

    public void remove() {
        throw new UnsupportedOperationException("Remove from iterator is not supported by transient iterator");
    }

    public EntityId nextId() {
        return iter.next().getId();
    }

    public boolean dispose() {
        throw new UnsupportedOperationException("Transient iterator doesn't support disposing.");
    }

    public boolean skip(int number) {
        while (number-- > 0 && hasNext()) {
            next();
        }
        return hasNext();
    }

    public boolean shouldBeDisposed() {
        return false; //TODO: revisit EntityIterator interface and remove these stub method
    }
}
