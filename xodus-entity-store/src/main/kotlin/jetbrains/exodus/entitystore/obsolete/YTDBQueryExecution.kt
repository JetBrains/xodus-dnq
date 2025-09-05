package jetbrains.exodus.entitystore.obsolete

import com.jetbrains.youtrackdb.api.query.ResultSet
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.query.*
import mu.KLogging

object YTDBQueryExecution : KLogging() {

    fun execute(query: YTDBQuery, tx: YTDBStoreTransaction): ResultSet {
        val sqlQuery = tx.buildSql(query)
        // val resultSet = tx.query(sqlQuery.sql, sqlQuery.params)
        val resultSet: ResultSet = TODO()

        // Log execution plan
        // ToDo: add System param to enable/disable logging of execution plan
        logger.debug {
            val executionPlan = resultSet.executionPlan!!.prettyPrint(10, 8)
            "Query: $sqlQuery, \n execution plan:\n  $executionPlan"
        }
        return resultSet
    }
}

internal fun YTDBStoreTransaction.buildSql(query: YTDBQuery): SqlQuery {
    val builder = SqlBuilder()
    query.sql(builder)
    this.queryCancellingPolicy?.let {
        check(it is YTDBQueryCancellingPolicy) { "Unsupported query cancelling policy: $it" }
        val timeoutQuery = YTDBQueryTimeout(it.timeoutMillis)
        timeoutQuery.sql(builder)
    }

    return builder.build()
}
