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