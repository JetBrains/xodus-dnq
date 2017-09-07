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

import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityId;
import jetbrains.exodus.entitystore.EntityIterator;

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
