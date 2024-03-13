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
package kotlinx.dnq.listener

import jetbrains.exodus.database.DNQListener
import kotlinx.dnq.XdEntity

interface XdEntityListener<in XD : XdEntity> : DNQListener<XD> {

    override fun addedSyncBeforeConstraints(added: XD) = Unit
    override fun addedSync(added: XD) = Unit

    override fun updatedSyncBeforeConstraints(old: XD, current: XD) = Unit
    override fun updatedSync(old: XD, current: XD) = Unit

    override fun removedSyncBeforeConstraints(removed: XD) = Unit
    override fun removedSync(removed: XD) = Unit
}
