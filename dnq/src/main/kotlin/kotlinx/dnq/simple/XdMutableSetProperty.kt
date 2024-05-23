/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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

import com.jetbrains.teamsys.dnq.database.reattachTransient
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.orientdb.OComparableSet
import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.util.reattachAndGetPrimitiveValue
import kotlin.reflect.KProperty

class XdMutableSetProperty<in R : XdEntity, T : Comparable<T>>(dbPropertyName: String?) :
        XdConstrainedProperty<R, MutableSet<T>>(
                dbPropertyName,
                emptyList(),
                XdPropertyRequirement.OPTIONAL,
                PropertyType.PRIMITIVE) {

    override fun getValue(thisRef: R, property: KProperty<*>): MutableSet<T> {
        return BoundMutableSet(thisRef.entity, property.dbName)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.reattachAndGetPrimitiveValue<OComparableSet<*>>(property.dbName) != null
    }
}

class BoundMutableSet<T : Comparable<T>>(val entity: Entity, val dbPropertyName: String) : MutableSet<T> {

    private fun set(value: OComparableSet<T>?) {
        if (value == null){
            entity.reattachTransient().setProperty(dbPropertyName, OComparableSet(HashSet<T>()))
        } else {
            entity.reattachTransient().setProperty(dbPropertyName, value)
        }
    }

    private fun get(): OComparableSet<T>? {
        @Suppress("UNCHECKED_CAST")
        return entity.reattachTransient().getProperty(dbPropertyName) as OComparableSet<T>?
    }

    private inline fun update(operation: (OComparableSet<T>) -> Unit): Boolean {
        val propertyValue = get() ?: OComparableSet(HashSet())
        operation(propertyValue)
        return if (propertyValue.isDirty) {
            set(propertyValue)
            true
        } else {
            false
        }
    }

    override fun add(element: T) = update {
        it.add(element)
    }

    override fun addAll(elements: Collection<T>) = update {
        elements.forEach { element -> it.add(element) }
    }

    override fun clear() {
        update {
            it.clear()
        }
    }

    override fun iterator(): MutableIterator<T> {
        return (get() ?: OComparableSet(HashSet())).iterator()
    }

    override fun remove(element: T) = update {
        it.remove(element)
    }

    override fun removeAll(elements: Collection<T>) = update {
        elements.forEach { element -> it.remove(element) }
    }

    override fun retainAll(elements: Collection<T>) = update {
        (it - elements).forEach { element -> it.remove(element) }
    }

    override val size: Int
        get() = get()?.size ?: 0

    override fun contains(element: T): Boolean {
        return get()?.let { element in it } ?: false
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        val propertyValue = get()
        return if (propertyValue != null) {
            elements.all { element -> propertyValue.contains(element) }
        } else {
            elements.isEmpty()
        }
    }

    override fun isEmpty(): Boolean {
        return get()?.isEmpty() ?: true
    }
}
