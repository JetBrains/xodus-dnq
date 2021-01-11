/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package kotlinx.dnq.store

import jetbrains.exodus.database.*
import jetbrains.exodus.entitystore.Entity

object DummyTransientChangesMultiplexer : ITransientChangesMultiplexer {
    override fun flushed(
            session: TransientStoreSession,
            oldChangesTracker: TransientChangesTracker,
            changesDescription: Set<TransientEntityChange>) {
        oldChangesTracker.dispose()
    }

    override fun onClose(transientEntityStore: TransientEntityStore) = Unit

    override fun addListener(e: Entity, listener: IEntityListener<*>) {
        TODO("not implemented")
    }

    override fun removeListener(e: Entity, listener: IEntityListener<*>) {
        TODO("not implemented")
    }

    override fun addListener(entityType: String, listener: IEntityListener<*>) {
        TODO("not implemented")
    }

    override fun removeListener(entityType: String, listener: IEntityListener<*>) {
        TODO("not implemented")
    }
}
