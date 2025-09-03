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
@file:OptIn(ExperimentalStdlibApi::class)

package jetbrains.exodus.query.metadata

import com.jetbrains.youtrack.db.api.record.Vertex
import jetbrains.exodus.bindings.ComparableSet
import jetbrains.exodus.bindings.StringBinding
import jetbrains.exodus.entitystore.StoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import jetbrains.exodus.util.ByteArraySizedInputStream
import jetbrains.exodus.util.LightOutputStream
import org.junit.Assert
import java.io.ByteArrayInputStream
import kotlin.random.Random


internal fun StoreTransaction.assertOrientContainsAllTheEntities(pile: PileOfEntities) {
    for (type in pile.types) {
        for (record in this.getAll(type).map { it as YTDBVertexEntity }) {
            val entity = pile.getEntity(type, record.getTestId())
            record.assertEquals(entity)
        }
    }
}

internal fun YTDBVertexEntity.assertEquals(expected: Entity) {
    val actualDocument = this
    val actual = this

    Assert.assertEquals(expected.id, actualDocument.getTestId())
    for (propName in expected.props.keys) {
        val expectedValue = expected.props.getValue(propName)
        val actualValue = actual.getProperty(propName)
        if (expectedValue is String && expectedValue != actualValue) {
            // expected string may be a Classic Xodus "broken" string
            // fix it and compare again
            val fixedExpectedValue = expectedValue.encodeToByteArray().decodeToString()
            Assert.assertEquals(fixedExpectedValue, actualValue)
        } else {
            Assert.assertEquals(expectedValue, actualValue)
        }
    }
    for ((blobName, blobValue) in expected.blobs) {
        val actualValue = actual.getBlob(blobName)!!.readAllBytes()
        Assert.assertEquals(blobValue, actualValue.decodeToString())
    }
    for ((blobName, blobValue) in expected.stringBlobs) {
        val actualValue = actual.getBlobString(blobName)!!
        Assert.assertEquals(blobValue, actualValue)
    }

    for (expectedLink in expected.links) {
        val actualLinks = actual.getLinks(expectedLink.name).toList()
        val tartedActual = actualLinks.first { it.getProperty("id") == expectedLink.targetId }
        Assert.assertEquals(expectedLink.targetType, tartedActual.type)
    }
}

internal fun YTDBVertexEntity.getTestId(): Int = getProperty("id") as Int

internal fun Vertex.getTestId(): Int = getProperty<Int>("id") as Int

internal fun StoreTransaction.createEntities(pile: PileOfEntities) {
    for (type in pile.types) {
        for (entity in pile.getAll(type)) {
            this.createEntity(entity)
        }
    }
    for (type in pile.types) {
        for (entity in pile.getAll(type)) {
            this.createLinks(entity)
        }
    }
}

internal fun StoreTransaction.createEntity(entity: Entity) {
    val e = this.newEntity(entity.type)
    e.setProperty("id", entity.id)

    for ((name, value) in entity.props) {
        e.setProperty(name, value)
    }
    for ((name, set) in entity.sets) {
        e.setProperty(name, set)
    }
    for ((name, value) in entity.blobs) {
        e.setBlob(name, ByteArrayInputStream(value.encodeToByteArray()))
    }
    for ((name, value) in entity.stringBlobs) {
        e.setBlobString(name, value)
    }
}

internal fun StoreTransaction.createLinks(entity: Entity) {
    val xEntity = this.getAll(entity.type).first { it.getProperty("id") == entity.id }
    for (link in entity.links) {
        val targetXEntity =
            this.getAll(link.targetType).first { it.getProperty("id") == link.targetId }
        xEntity.addLink(link.name, targetXEntity)
    }
}

internal class PileOfEntities {
    private val typeToEntities = mutableMapOf<String, MutableMap<Int, Entity>>()

    val types: Set<String> get() = typeToEntities.keys

    fun add(entity: Entity) {
        typeToEntities.getOrPut(entity.type) { mutableMapOf() }[entity.id] = entity
    }

    fun getAll(type: String): Collection<Entity> = typeToEntities.getValue(type).values

    fun getEntity(type: String, id: Int): Entity = typeToEntities.getValue(type).getValue(id)
}

internal data class Entity(
    val type: String,
    val id: Int,
    val props: Map<String, Comparable<*>> = mapOf(),
    val sets: Map<String, ComparableSet<*>> = mapOf(),
    val blobs: Map<String, String> = mapOf(),
    val stringBlobs: Map<String, String> = mapOf(),
    val links: List<Link> = listOf()
)

internal data class Link(
    val name: String,
    val targetType: String,
    val targetId: Int,
)

internal fun pileOfEntities(vararg entities: Entity): PileOfEntities {
    val pile = PileOfEntities()
    for (entity in entities) {
        pile.add(entity)
    }
    return pile
}

internal fun eProps(type: String, id: Int, vararg props: Pair<String, Comparable<*>>): Entity {
    return Entity(type, id, props.toMap())
}

internal fun <T> eSets(
    type: String,
    id: Int,
    vararg sets: Pair<String, Set<T>>
): Entity where T : Comparable<T> {
    val mapOfSets = sets.associate { (name, set) ->
        Pair(name, ComparableSet<T>(set))
    }
    return Entity(type, id, sets = mapOfSets)
}

internal fun eBlobs(type: String, id: Int, vararg blobs: Pair<String, String>): Entity {
    return Entity(type, id, blobs = blobs.toMap())
}

internal fun eStringBlobs(type: String, id: Int, vararg blobs: Pair<String, String>): Entity {
    return Entity(type, id, stringBlobs = blobs.toMap())
}

internal fun eLinks(type: String, id: Int, vararg links: Link): Entity {
    return Entity(type, id, links = links.toList())
}

private fun String.toBytesXodusPropertyStyle(dropEOF: Boolean = false): ByteArray {
    val stream = LightOutputStream()
    StringBinding.BINDING.writeObject(stream, this)
    val size = if (dropEOF) stream.size() - 1 else stream.size()
    return ByteArray(size) {
        stream.bufferBytes[it]
    }
}

private fun ByteArray.toStringXodusPropertyStyle(addEOF: Boolean = false): String {
    val arr = if (addEOF) {
        val newArr = ByteArray(this.size + 1)
        this.copyInto(newArr)
        newArr[newArr.lastIndex] = 0
        newArr
    } else this
    return StringBinding.BINDING.readObject(ByteArraySizedInputStream(arr))
}

private fun getBrokenXodusString(): String {
    val rawBytesGottenFromAProdXodus =
        "82e197b0cf83c4a7e2b1a2ceb1c59fc4a7ceb96d20e1b9a8c4a7ceb1e1b8adeda080c4a700".hexToByteArray()
    val originalBytesForXodus =
        rawBytesGottenFromAProdXodus.sliceArray(1 until rawBytesGottenFromAProdXodus.size)
    return originalBytesForXodus.toStringXodusPropertyStyle()
}

private fun randomUtf8String(@Suppress("SameParameterValue") size: Int): String = buildString {
    while (this.length < size) {
        val char = Random.nextInt(0, 0xFFFF).toChar()
        if (!char.isSurrogate()) {
            append(char)
        }
    }
}

