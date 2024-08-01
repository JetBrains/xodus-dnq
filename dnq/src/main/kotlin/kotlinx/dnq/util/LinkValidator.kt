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
package kotlinx.dnq.util

import jetbrains.exodus.query.metadata.AssociationEndType

internal class LinkValidator {
    private val undirectedLinksWithMissingOppositeSide = HashSet<UndirectedLinkInfo>()

    fun oneMore(sourceEntity: String, sourceEnd: XdHierarchyNode.LinkProperty) {
        if (sourceEnd.delegate.endType != AssociationEndType.UndirectedAssociationEnd) {
            return
        }
        val linkInfo = UndirectedLinkInfo(
            sourceEntity,
            sourceProp = sourceEnd.dbPropertyName,
            targetEntity = sourceEnd.delegate.oppositeEntityType.entityType,
            targetProp = sourceEnd.delegate.dbOppositePropertyName
                ?: sourceEnd.delegate.oppositeField?.name
                ?: throw IllegalStateException("Undirected link ${sourceEntity}.${sourceEnd.dbPropertyName} does not have the oppositeField. It seems, something dramatic went wrong...")
        )
        if (!undirectedLinksWithMissingOppositeSide.remove(linkInfo.oppositeLink)) {
            undirectedLinksWithMissingOppositeSide.add(linkInfo)
        }
    }

    fun validate() {
        if (undirectedLinksWithMissingOppositeSide.isNotEmpty()) {
            throw IllegalStateException("""
                The following bi-directional links do not have properly configured links on the opposite side:
                ${undirectedLinksWithMissingOppositeSide.joinToString("\n") { "$it needs ${it.oppositeLink}" }}
            """.trimIndent())
        }
    }
}

private data class UndirectedLinkInfo(
    val sourceEntity: String,
    val sourceProp: String,
    val targetEntity: String,
    val targetProp: String
) {
    val oppositeLink: UndirectedLinkInfo
        get() = UndirectedLinkInfo(targetEntity, targetProp, sourceEntity, sourceProp)

    override fun toString(): String {
        return "${sourceEntity}.${sourceProp} -> ${targetEntity}.${targetProp}"
    }
}