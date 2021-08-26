/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package kotlinx.dnq.events

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*

open class Bar(entity: Entity) : XdEntity(entity) {
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

open class ExtraBar(entity: Entity) : Bar(entity) {
    companion object : XdNaturalEntityType<ExtraBar>()
}
