package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.entitystore.Entity

class PerEntityIncomingLinkViolation(
        linkName: String,
        val message: (entity: Entity) -> String
) : IncomingLinkViolation(linkName) {

    override fun buildDescription(entitiesCausedViolation: List<Entity>, hasMoreEntitiesCausedViolations: Boolean): Set<String> {
        return entitiesCausedViolation
                .map(message)
                .plus(listOfNotNull("and more...".takeIf { hasMoreEntitiesCausedViolations }))
                .toSet()
    }
}