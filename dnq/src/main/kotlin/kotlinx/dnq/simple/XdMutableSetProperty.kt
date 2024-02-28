/**
 * Copyright 2006 - 2023 JetBrains s.r.o.
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
package kotlinx.dnq.simple

import com.orientechnologies.orient.core.record.OVertex
import jetbrains.exodus.bindings.ComparableSet
import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
import kotlin.reflect.KProperty

class XdMutableSetProperty<in R : XdEntity, T : Comparable<T>>(
    dbPropertyName: String?,
    val clazz: Class<T>
) : XdConstrainedProperty<R, MutableSet<T>>(
        dbPropertyName,
        emptyList(),
        XdPropertyRequirement.OPTIONAL,
        PropertyType.PRIMITIVE
    ) {

    override fun getValue(thisRef: R, property: KProperty<*>): MutableSet<T> {
        return BoundMutableSet(thisRef.reload(), property.dbName)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.vertex.hasProperty(property.dbName)
    }
}

class BoundMutableSet<T : Comparable<T>>(val vertex: OVertex, val dbPropertyName: String) : MutableSet<T> {

    private fun set(value: ComparableSet<T>?) {
        when {
            value == null || value.isEmpty -> vertex.removeProperty<Set<T>>(dbPropertyName)
            else -> vertex.setProperty(dbPropertyName, value)
        }
        vertex.save<OVertex>()
    }

    private fun get(): ComparableSet<T>? {
        return vertex.getProperty(dbPropertyName) as ComparableSet<T>?
    }

    private inline fun update(operation: (ComparableSet<T>) -> Unit): Boolean {
        val propertyValue = get() ?: ComparableSet()
        operation(propertyValue)
        return if (propertyValue.isDirty) {
            set(propertyValue)
            true
        } else {
            false
        }
    }

    override fun add(element: T) = update {
        it.addItem(element)
    }

    override fun addAll(elements: Collection<T>) = update {
        elements.forEach { element -> it.addItem(element) }
    }

    override fun clear() {
        set(null)
    }

    override fun iterator(): MutableIterator<T> {
        return (get() ?: ComparableSet()).iterator()
    }

    override fun remove(element: T) = update {
        it.removeItem(element)
    }

    override fun removeAll(elements: Collection<T>) = update {
        elements.forEach { element -> it.removeItem(element) }
    }

    override fun retainAll(elements: Collection<T>) = update {
        (it - elements.toSet()).forEach { element -> it.removeItem(element) }
    }

    override val size: Int
        get() = get()?.size() ?: 0

    override fun contains(element: T): Boolean {
        return get()?.let { element in it } ?: false
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        val propertyValue = get()
        return if (propertyValue != null) {
            elements.all { element -> propertyValue.containsItem(element) }
        } else {
            elements.isEmpty()
        }
    }

    override fun isEmpty(): Boolean {
        return get()?.isEmpty ?: true
    }
}
