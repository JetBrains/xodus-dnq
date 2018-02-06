package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.entitystore.Entity

class PerTypeIncomingLinkViolation(
        linkName: String,
        val message: (linkedEntities: List<Entity>, hasMore: Boolean) -> String
) : IncomingLinkViolation(linkName) {

    override fun buildDescription(entitiesCausedViolation: List<Entity>, hasMoreEntitiesCausedViolations: Boolean): Set<String> {
        return setOf(message(entitiesCausedViolation, hasMoreEntitiesCausedViolations))
    }
}