package kotlinx.dnq.events

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*

class Bar(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<Bar>()

    var bar by xdStringProp()
}

class Foo(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<Foo>()

    var intField by xdIntProp()
}

class Goo(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<Goo>()

    val content by xdLink0_N(Foo)
}
