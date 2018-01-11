/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.linkConstraints

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.link.OnDeletePolicy.CLEAR

class A1(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<A1>()

    var l by xdLink1(A3, onTargetDelete = CASCADE)
}

class A2(entity: Entity) : A3(entity) {
    companion object : XdNaturalEntityType<A2>()
}

open class A3(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<A3>()
}

fun B1(b2: B2) = B1.new { this.b2 = b2 }

class B1(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<B1>()

    var b2 by xdLink1(B2, onTargetDelete = CASCADE)
}

fun B2(b3: B3) = B2.new { this.b3.add(b3) }

class B2(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<B2>()

    val b3 by xdLink0_N(B3::b2, onTargetDelete = CASCADE, onDelete = CASCADE)
}

fun B3(b4: B4) = B3.new { this.b4.add(b4) }

class B3(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<B3>()

    val b4 by xdLink1_N(B4::b3, onTargetDelete = CASCADE)
    val b2: B2? by xdLink0_1(B2::b3)
}

class B4(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<B4>()

    var b3: B3 by xdLink1(B3::b4)
}

class C1(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<C1>()

    var c2: C2? by xdLink0_1(C2::c1)
}

fun C2(c1: C1) = C2.new { this.c1 = c1 }

class C2(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<C2>()

    var c1 by xdLink0_1(C1::c2, onTargetDelete = CLEAR)
}

class D1(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<D1>()

    val d2 by xdLink0_N(D2, onDelete = CASCADE, onTargetDelete = CLEAR)
}

fun D2(name: String) = D2.new { this.name = name }

class D2(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<D2>()

    var name by xdStringProp()
}

class E1(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<E1>()

    var e2 by xdLink0_1(E2, onTargetDelete = CLEAR)
}

class E2(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<E2>()
}

class F1(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<F1>()

    val f2 by xdLink0_N(F2::f1, onTargetDelete = CASCADE, onDelete = CASCADE)
}


class F2(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<F2>()

    var f1: F1? by xdLink0_1(F1::f2, onDelete = CLEAR)
}
