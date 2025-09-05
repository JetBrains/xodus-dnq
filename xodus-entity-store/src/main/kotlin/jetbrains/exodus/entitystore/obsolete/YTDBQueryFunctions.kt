package jetbrains.exodus.entitystore.obsolete

import jetbrains.exodus.entitystore.youtrackdb.query.YTDBClassSelect
import jetbrains.exodus.entitystore.youtrackdb.query.YTDBDifferenceSelect
import jetbrains.exodus.entitystore.youtrackdb.query.YTDBDistinctSelect
import jetbrains.exodus.entitystore.youtrackdb.query.YTDBIntersectSelect
import jetbrains.exodus.entitystore.youtrackdb.query.YTDBRecordIdSelect
import jetbrains.exodus.entitystore.youtrackdb.query.YTDBSelect
import jetbrains.exodus.entitystore.youtrackdb.query.YTDBUnionSelect
import jetbrains.exodus.entitystore.youtrackdb.query.and
import jetbrains.exodus.entitystore.youtrackdb.query.andNot
import jetbrains.exodus.entitystore.youtrackdb.query.merge
import jetbrains.exodus.entitystore.youtrackdb.query.or

object YTDBQueryFunctions {

    fun intersect(left: YTDBSelect, right: YTDBSelect): YTDBSelect {
        return when {
            left is YTDBRecordIdSelect && right is YTDBRecordIdSelect -> {
                ensureLimitIsNotUsed(left, right)
                ensureSkipIsNotUsed(left, right)

                val newOrder = left.order.merge(right.order)
                val ids = left.recordIds.intersect(right.recordIds.toSet())
                YTDBRecordIdSelect(ids, newOrder)
            }

            left is YTDBClassSelect && right is YTDBClassSelect && isSameClassName(left, right) -> {
                ensureInvariants(left, right)
                val newCondition = left.condition.and(right.condition)
                val newOrder = left.order.merge(right.order)
                YTDBClassSelect(left.className, newCondition, newOrder)
            }

            else -> {
                YTDBIntersectSelect(left, right)
            }
        }
    }

    fun union(left: YTDBSelect, right: YTDBSelect): YTDBSelect {
        return when {
            left is YTDBRecordIdSelect && right is YTDBRecordIdSelect -> {
                ensureLimitIsNotUsed(left, right)
                ensureSkipIsNotUsed(left, right)

                val newOrder = left.order.merge(right.order)
                val ids = (left.recordIds + right.recordIds).toSet()
                YTDBRecordIdSelect(ids, newOrder)
            }

            left is YTDBClassSelect && right is YTDBClassSelect && isSameClassName(left, right) -> {
                ensureInvariants(left, right)
                val newCondition = left.condition.or(right.condition)
                val newOrder = left.order.merge(right.order)
                YTDBClassSelect(left.className, newCondition, newOrder)
            }

            else -> {
                YTDBUnionSelect(left, right)
            }
        }
    }

    fun difference(left: YTDBSelect, right: YTDBSelect): YTDBSelect {
        return when {

            left is YTDBClassSelect && right is YTDBClassSelect && isSameClassName(left, right) -> {
                ensureInvariants(left, right)
                val newCondition = left.condition.andNot(right.condition)
                val newOrder = left.order.merge(right.order)
                YTDBClassSelect(left.className, newCondition, newOrder)
            }

            else -> {
                YTDBDifferenceSelect(left, right)
            }
        }
    }

    fun distinct(source: YTDBSelect): YTDBSelect {
        return YTDBDistinctSelect(source)
    }

    fun reverse(query: YTDBSelect): YTDBSelect {
        val order = query.order?.reverse() ?: return query
        return query.withOrder(order)
    }

    private fun ensureInvariants(left: YTDBClassSelect, right: YTDBClassSelect) {
        ensureSkipIsNotUsed(left, right)
        ensureLimitIsNotUsed(left, right)
    }

    private fun ensureSkipIsNotUsed(left: YTDBSelect, right: YTDBSelect) {
        val lazyMessage = { "Skip can not be used for sub-query" }
        check(left.skip == null, lazyMessage)
        check(right.skip == null, lazyMessage)
    }

    private fun ensureLimitIsNotUsed(left: YTDBSelect, right: YTDBSelect) {
        val lazyMessage = { "Take can not be used for sub-query" }
        check(left.limit == null, lazyMessage)
        check(right.limit == null, lazyMessage)
    }

    private fun isSameClassName(left: YTDBClassSelect, right: YTDBClassSelect): Boolean {
        return left.className == right.className
    }
}