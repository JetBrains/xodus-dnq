package kotlinx.dnq.events

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*

class Bar(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Bar>()

    var bar by xdStringProp()
}

class Foo(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Foo>()

    var intField by xdIntProp()
}

class Goo(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Goo>()

    val content by xdLink0_N(Foo)
}
