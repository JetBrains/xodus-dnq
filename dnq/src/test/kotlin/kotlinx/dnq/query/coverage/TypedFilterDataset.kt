/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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
package kotlinx.dnq.query.coverage

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.youtrackdb.RIDEntityId
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.xdNullableLongProp
import kotlinx.dnq.xdRequiredStringProp
import kotlinx.dnq.xdStringProp
import java.security.MessageDigest
import java.util.Locale

open class Account(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Account>()
    var rowKey by xdRequiredStringProp()
    var handle by xdRequiredStringProp()
    var lastSeenAt by xdNullableLongProp()
}

open class ServiceAccount(entity: Entity) : Account(entity) {
    companion object : XdNaturalEntityType<ServiceAccount>()
}

class RobotAccount(entity: Entity) : ServiceAccount(entity) {
    companion object : XdNaturalEntityType<RobotAccount>()
}

open class CatalogItem(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<CatalogItem>()
    var rowKey by xdRequiredStringProp()
    var caption by xdStringProp()
}

class SystemItem(entity: Entity) : CatalogItem(entity) {
    companion object : XdNaturalEntityType<SystemItem>()
}

class HiddenItem(entity: Entity) : CatalogItem(entity) {
    companion object : XdNaturalEntityType<HiddenItem>()
}

/** Facts and logical keys are repeatable. Database identities are resolved after commit. */
class TypedFilterDataset(store: TransientEntityStore, val rows: List<Row> = generateRows()) {
    enum class Kind(val parent: Kind? = null) {
        ACCOUNT, SERVICE(ACCOUNT), ROBOT(SERVICE), CATALOG, SYSTEM(CATALOG), HIDDEN(CATALOG);

        fun isA(type: Kind, polymorphic: Boolean): Boolean =
            this == type || (polymorphic && parent?.isA(type, true) == true)
    }

    data class Row(val key: String, val kind: Kind, val text: String?, val lastSeen: Long? = null)

    val entities: Map<String, XdEntity>
    val ids: Map<String, EntityId>
    val digest: String = MessageDigest.getInstance("SHA-256")
        .digest(rows.joinToString("\n") { "${it.key}|${it.kind}|${it.text}|${it.lastSeen}" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    init {
        require(rows.map { it.key }.distinct().size == rows.size)
        entities = store.transactional {
            // Reverse logical order deliberately. Neither oracle nor tie evidence uses insertion order.
            rows.reversed().associate { row ->
                val entity = when (row.kind) {
                    Kind.ACCOUNT -> Account.new()
                    Kind.SERVICE -> ServiceAccount.new()
                    Kind.ROBOT -> RobotAccount.new()
                    Kind.CATALOG -> CatalogItem.new()
                    Kind.SYSTEM -> SystemItem.new()
                    Kind.HIDDEN -> HiddenItem.new()
                }
                when (entity) {
                    is Account -> {
                        entity.rowKey = row.key
                        entity.handle = requireNotNull(row.text)
                        entity.lastSeenAt = row.lastSeen
                    }
                    is CatalogItem -> {
                        entity.rowKey = row.key
                        entity.caption = row.text
                    }
                }
                row.key to entity
            }
        }
        ids = store.transactional { entities.mapValues { it.value.entity.id } }
        check(ids.values.distinct().size == rows.size)
    }

    fun rid(key: String) = (ids.getValue(key) as RIDEntityId).asOId()

    /** Membership is evaluated only from row facts and the declared parent chain. */
    fun accountPage(prefix: String, polymorphic: Boolean, skip: Int = 0, limit: Int = 51): List<EntityId> =
        page(rows.filter {
            it.kind.isA(Kind.ACCOUNT, polymorphic) && !it.kind.isA(Kind.SERVICE, polymorphic) &&
                it.text!!.lowercase(Locale.ENGLISH).startsWith(prefix.lowercase(Locale.ENGLISH))
        }, Comparator { a, b ->
            nullsLast(a.lastSeen, b.lastSeen, descending = true)
                .takeUnless { it == 0 } ?: compareText(a.text, b.text)
        }, skip, limit)

    fun catalogPage(polymorphic: Boolean, skip: Int = 0, limit: Int = 51): List<EntityId> =
        page(rows.filter {
            it.kind.isA(Kind.CATALOG, polymorphic) && !it.kind.isA(Kind.SYSTEM, polymorphic) &&
                !it.kind.isA(Kind.HIDDEN, polymorphic)
        }, Comparator { a, b -> compareText(a.text, b.text) }, skip, limit)

    private fun page(selected: List<Row>, order: Comparator<Row>, skip: Int, limit: Int): List<EntityId> {
        require(skip >= 0 && limit >= 0)
        // The pinned provider breaks full-key ties by physical RID, in the last key's direction.
        // Both target trees end in an ascending key. Logical EntityId order is not RID order.
        return selected.sortedWith(order.thenComparing { a, b -> rid(a.key).compareTo(rid(b.key)) })
            .drop(skip).take(limit).map { ids.getValue(it.key) }
    }

    private fun compareText(a: String?, b: String?): Int =
        nullsLast(a?.lowercase(Locale.ENGLISH), b?.lowercase(Locale.ENGLISH))

    private fun <T : Comparable<T>> nullsLast(a: T?, b: T?, descending: Boolean = false): Int = when {
        a == null -> if (b == null) 0 else 1
        b == null -> -1
        descending -> b.compareTo(a)
        else -> a.compareTo(b)
    }

    companion object {
        /** Size can grow without changing the row-to-identity protocol used by correctness checks. */
        fun generateRows(size: Int = 72): List<Row> {
            require(size >= 60)
            val rows = mutableListOf<Row>()
            repeat(size) { i ->
                val suffix = (i / 2).toString().padStart(6, '0')
                rows += Row("a-$i", Kind.ACCOUNT, if (i % 2 == 0) "a$suffix" else "A$suffix",
                    if (i >= size - 20) null else (i / 8).toLong())
                rows += Row("c-$i", Kind.CATALOG,
                    if (i >= size - 20) null else if (i % 2 == 0) "item$suffix" else "Item$suffix")
            }
            listOf(Kind.SERVICE, Kind.ROBOT).forEach { kind ->
                rows += Row("$kind-match", kind, "a000000", 100L)
                rows += Row("$kind-other", kind, "z-service", null)
            }
            listOf(Kind.SYSTEM, Kind.HIDDEN).forEach { kind ->
                rows += Row("$kind-match", kind, "Item000000")
                rows += Row("$kind-null", kind, null)
            }
            rows += Row("a-unicode", Kind.ACCOUNT, "Änne", 1L)
            rows += Row("a-other", Kind.ACCOUNT, "zebra", null)
            rows += Row("a-tie-1", Kind.ACCOUNT, "a-tie", 20L)
            rows += Row("a-tie-2", Kind.ACCOUNT, "a-tie", 20L)
            rows += Row("c-tie-1", Kind.CATALOG, "same")
            rows += Row("c-tie-2", Kind.CATALOG, "same")
            return rows
        }
    }
}
