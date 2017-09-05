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

import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.query.QueryEngine;
import jetbrains.exodus.query.SortEngine;

public class TransientSortEngineImpl extends SortEngine {

    private TransientEntityStore store;

    public TransientSortEngineImpl() {
    }

    public TransientSortEngineImpl(QueryEngine queryEngine) {
        super(queryEngine);
    }

    public void setEntityStore(TransientEntityStore store) {
        this.store = store;
    }

    @Override
    protected Entity attach(Entity entity) {
        // TODO: seems that it's needed only for compatibility
        final TransientStoreSession threadSession = store.getThreadSession();
        return (threadSession == null ?
                entity :
                threadSession.newEntity(entity)
        );
    }
}
