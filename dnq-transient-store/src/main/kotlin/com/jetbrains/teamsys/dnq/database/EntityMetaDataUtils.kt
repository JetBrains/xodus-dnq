/**
 * Copyright 2006 - 2022 JetBrains s.r.o.
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
package com.jetbrains.teamsys.dnq.database

import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import jetbrains.exodus.database.LinkChange
import jetbrains.exodus.database.TransientChangesTracker
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.metadata.EntityMetaData

object EntityMetaDataUtils {

    @JvmStatic
    fun getRequiredIfProperties(emd: EntityMetaData, e: Entity): Set<String> {
        val lifecycle = (e as? TransientEntity)
                ?.lifecycle
                ?: return emptySet()
        return lifecycle.getRequiredIfProperties(emd, e)
    }

    @JvmStatic
    fun hasParent(emd: EntityMetaData, e: TransientEntity, tracker: TransientChangesTracker): Boolean {
        val aggregationChildEnds = emd.aggregationChildEnds
        return if (e.isNew || parentChanged(aggregationChildEnds, tracker.getChangedLinksDetailed(e))) {
            aggregationChildEnds.any { AssociationSemantics.getToOne(e, it) != null }
        } else {
            true
        }
    }

    private fun parentChanged(aggregationChildEnds: Set<String>, changedLinks: Map<String, LinkChange>?): Boolean {
        return changedLinks != null && aggregationChildEnds.any { it in changedLinks }
    }
}
