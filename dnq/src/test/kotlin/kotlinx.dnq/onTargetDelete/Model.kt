package kotlinx.dnq.onTargetDelete

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.link.OnDeletePolicy.CLEAR

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

fun B1(b2: B2) = B1.new { this.b2 = b2 }

class B1(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<B1>()

    var b2 by xdLink1(B2, onTargetDelete = CASCADE)
}

fun B2(b3: B3) = B2.new { this.b3.add(b3) }

class B2(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<B2>()

    val b3 by xdLink0_N(B3::b2, onTargetDelete = CASCADE, onDelete = CASCADE)
}

fun B3(b4: B4) = B3.new { this.b4.add(b4) }

class B3(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<B3>()

    val b4 by xdLink1_N(B4::b3, onTargetDelete = CASCADE)
    val b2: B2? by xdLink0_1(B2::b3)
}

class B4(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<B4>()

    var b3: B3 by xdLink1(B3::b4)
}

class C1(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<C1>()

    var c2: C2? by xdLink0_1(C2::c1)
}

fun C2(c1: C1) = C2.new { this.c1 = c1 }

class C2(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<C2>()

    var c1 by xdLink0_1(C1::c2, onTargetDelete = CLEAR)
}

class D1(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<D1>()

    val d2 by xdLink0_N(D2, onDelete = CASCADE, onTargetDelete = CLEAR)
}

fun D2(name: String) = D2.new { this.name = name }

class D2(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<D2>()

    var name by xdStringProp()
}

class E1(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<E1>()

    var e2 by xdLink0_1(E2, onTargetDelete = CLEAR)
}

class E2(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<E2>()
}

class F1(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<F1>()

    val f2 by xdLink0_N(F2::f1, onTargetDelete = CASCADE, onDelete = CASCADE)
}


class F2(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<F2>()

    var f1: F1? by xdLink0_1(F1::f2, onDelete = CLEAR)
}
