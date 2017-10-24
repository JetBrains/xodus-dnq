package kotlinx.dnq.onTargetDelete

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.xdLink1

fun A1(a3: A3) = A1.new { l = a3 }

class A1(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<A1>()

    var l by xdLink1(A3, onTargetDelete = CASCADE)
}

class A2(entity: Entity) : A3(entity) {
    companion object : XdNaturalEntityType<A2>()
}

open class A3(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<A3>()
}
