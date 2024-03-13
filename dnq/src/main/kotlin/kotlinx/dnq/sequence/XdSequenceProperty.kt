/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.sequence

import com.jetbrains.teamsys.dnq.database.threadSessionOrThrow
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Sequence
import kotlinx.dnq.XdEntity
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class XdSequenceProperty<in R : XdEntity>(val dbPropertyName: String?) : ReadOnlyProperty<R, Sequence> {
    override fun getValue(thisRef: R, property: KProperty<*>): Sequence {
        val transientEntity = thisRef.entity as TransientEntity
        val session = transientEntity.store.threadSessionOrThrow
        return session.getSequence("${transientEntity.id}${dbPropertyName ?: property.name}")
    }
}
