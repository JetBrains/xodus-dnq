/**
 * Copyright 2006 - 2025 JetBrains s.r.o.
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
package kotlinx.dnq.sample

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.query.*
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.util.initMetaData
import java.io.File

// Version of "TinkerPop" modern Graph, but without weights on edges
interface ModernVertex {
    val name: String
}

class Temp(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Temp>()

    val name by xdRequiredStringProp()
}

class Person(entity: Entity) : XdEntity(entity), ModernVertex {
    companion object : XdNaturalEntityType<Person>()

    override
    var name by xdRequiredStringProp()
    var age by xdRequiredIntProp()

    // need an explicit type here to make kotlin compiler happy
    val knows: XdMutableQuery<Person> by xdLink0_N(Person::isKnownBy)
    val isKnownBy: XdMutableQuery<Person> by xdLink0_N(Person::knows)

    val created: XdMutableQuery<Software> by xdLink0_N(Software::createdBy)
}

class Software(entity: Entity) : XdEntity(entity), ModernVertex {
    companion object : XdNaturalEntityType<Software>()

    override
    var name by xdRequiredStringProp(unique = true)
    var lang by xdRequiredStringProp()

    val createdBy: XdMutableQuery<Person> by xdLink0_N(Person::created)
}

fun main(args: Array<String>) {

    XdModel.registerNodes(Person, Software)

    val store = StaticStoreContainer.init(
        dbFolder = File(System.getProperty("user.home"), ".dnq-tinkerpop-modern-demo"),
        entityStoreName = "tinkerpop-modern"
    )

    try {
        // Initialize DNQ metadata
        initMetaData(XdModel.hierarchy, store)
        println("Metadata initialized.")

        // clearing existing data, if any
        store.transactional { ModernGraph.clear() }
        // initializing the new graph
        val graph = store.transactional { ModernGraph.initialize() }

        queryDemo(store, graph)
    } finally {
        StaticStoreContainer.dbProvider!!.close()
    }
}

data class ModernGraph(
    val marko: Person,
    val vadas: Person,
    val josh: Person,
    val peter: Person,
    val lop: Software,
    val ripple: Software
) {
    companion object {

        fun clear() {
            val shouldClear = Person.all().isNotEmpty || Software.all().isNotEmpty
            if (shouldClear) {
                Person.all().forEach { it.delete() }
                Software.all().forEach { it.delete() }
                println("Database already contained some data. Cleared everything.")
            }
        }

        fun initialize(): ModernGraph {
            val lop = Software.new { name = "lop"; lang = "java" }
            val ripple = Software.new { name = "ripple"; lang = "java" }

            val josh = Person.new {
                name = "josh"; age = 32
                created.addAll(listOf(lop, ripple))
            }
            val peter = Person.new {
                name = "peter"; age = 35
                created.add(lop)
            }
            val vadas = Person.new {
                name = "vadas"; age = 27
            }

            val marko = Person.new {
                name = "marko"; age = 29
                created.add(lop)
                knows.addAll(listOf(josh, vadas))
            }

            println("Initialized Modern graph")
            return ModernGraph(marko, vadas, josh, peter, lop, ripple)
        }
    }
}

fun queryDemo(store: TransientEntityStore, graph: ModernGraph) {

    val queries = mapOf(
        "all people" to {
            Person.all()
        },
        "all software" to {
            Software.all()
        },
        "people named Marko" to {
            Person.query(Person::name eq "marko")
        },
        "people whose name starts with 'v'" to {
            Person.query(Person::name startsWith "v")
        },
        "people older than 30" to {
            Person.filter { it.age gt 30 }
        },
        "people younger than 30 who created anything" to {
            Person.filter { it.age le 30 and it.created.isNotEmpty() }
        },
        "people who created lop" to {
            Person.filter { it.created.contains(graph.lop) }
        },
        "people who participated in more than 1 project" to {
            // this returns emtpy result atm, why?
            Person.filter { it.created.size() ge 2 }
        },
        "people who created a project whose name starts with 'r'" to {
            Software
                .filter { it.name startsWith "r" }
                .flatMapDistinct { it.createdBy }
        },
        "people who know Josh" to {
            Person
                .filter { it.knows.contains(graph.josh) }
        },
        "people who know people who created lop" to {
            Person
                .filter { it.created.contains(graph.lop) }
                .flatMapDistinct { it.isKnownBy }
        }
    )

    queries.forEach { (description, query) ->
        store.transactional {
            val names = query().toList().map { it.name }
            println("$description : $names")
        }
    }
}
