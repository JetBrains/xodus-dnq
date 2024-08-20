package jetbrains.exodus.database

interface TransientEntityChange {
    val transientEntity: TransientEntity
    val changedProperties: Set<String>?
    val changedLinksDetailed: Map<String, LinkChange>?
    val changeType: EntityChangeType
    val snapshotEntity: TransientEntity

    override fun toString(): String
}
