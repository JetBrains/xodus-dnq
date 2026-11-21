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

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransactionImpl
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.*
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.util.initMetaData
import org.apache.tinkerpop.gremlin.process.traversal.Traversal
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Random
import java.util.function.Supplier

/**
 * Measures how long the Account and CatalogItem queries from XD-1310 take on repeatable data.
 * This lets us compare the same queries before and after an optimizer change.
 * Each query filters by entity type and returns a sorted page.
 * The benchmark checks that the page contains the expected entities in the expected order.
 *
 * Run this benchmark through `scripts/benchmarks/xd-1310.sh`.
 * The regular test suite does not run it.
 */
object TypedFilterBenchmark {
    private fun event(kind: String, value: Any = "") {
        println("@@\t$kind\t${value.toString().replace("\n", "\\n").replace("\t", "\\t")}")
        System.out.flush()
    }

    private fun acknowledged(name: String) {
        event("ready", name)
        check(readlnOrNull() == "GO") { "Supervisor acknowledgement missing" }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5) { "domain population distribution output-directory diagnostic-only" }
        val domain = args[0]
        val population = args[1].toInt()
        val distribution = args[2].toInt()
        val output = File(args[3]).apply { check(isDirectory) }
        val diagnosticOnly = args[4].toBooleanStrict()
        require(domain in listOf("account", "catalog") && population >= 100)
        require(if (domain == "account") distribution in listOf(1, 25, 75) else distribution in listOf(1, 25))
        try {
            run(domain, population, distribution, output, diagnosticOnly)
        } catch (failure: Throwable) {
            event(if (failure is ObserverFailure) "observer-failure" else "assertion-failure", failure)
            failure.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }

    // Freeze this generator and the operation below for paired original-product reruns.
    private fun rows(domain: String, size: Int, distribution: Int): List<TypedFilterDataset.Row> {
        val random = Random(1310)
        val shuffled = (0 until size).shuffled(kotlin.random.Random(1310))
        val match = shuffled.take(size * distribution / 100).toSet()
        return (0 until size).map { i ->
            val kind = if (domain == "account") when (i % 10) {
                0 -> TypedFilterDataset.Kind.ROBOT
                1 -> TypedFilterDataset.Kind.SERVICE
                else -> TypedFilterDataset.Kind.ACCOUNT
            } else when {
                distribution == 1 && i == 0 -> TypedFilterDataset.Kind.SYSTEM
                distribution == 1 && i == 1 -> TypedFilterDataset.Kind.HIDDEN
                distribution != 1 && i % 4 == 0 -> TypedFilterDataset.Kind.SYSTEM
                distribution != 1 && i % 4 == 1 -> TypedFilterDataset.Kind.HIDDEN
                else -> TypedFilterDataset.Kind.CATALOG
            }
            val suffix = (i / 2 % 101).toString().padStart(3, '0')
            val text = if (domain == "account") (if (i in match) {
                if (i % 2 == 0) "a" else "A"
            } else if (i % 19 == 0) "Ä" else "z") + suffix
            else if (i % 17 == 0) null else (if (i % 2 == 0) "Item" else "item") + suffix
            TypedFilterDataset.Row("$domain-$i", kind, text,
                if (domain != "account" || i % 13 == 0) null else random.nextInt(64).toLong())
        }
    }

    private fun query(domain: String): XdQuery<XdEntity> = if (domain == "account") {
        ((Account.all() exclude ServiceAccount.all()) intersect Account.all().filter { it.handle startsWith "a" })
            .sortedBy(Account::handle).sortedBy(Account::lastSeenAt, asc = false).take(51)
    } else {
        ((CatalogItem.all() exclude SystemItem.all()) intersect (CatalogItem.all() exclude HiddenItem.all()))
            .sortedBy(CatalogItem::caption).take(51)
    }

    private fun resources(): String {
        val runtime = Runtime.getRuntime()
        val gc = ManagementFactory.getGarbageCollectorMXBeans().joinToString { "${it.name}:${it.collectionCount}:${it.collectionTime}" }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val allocated = if (bean.isThreadAllocatedMemorySupported) bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) else -1
        return "heapUsed=${runtime.totalMemory() - runtime.freeMemory()} heapCommitted=${runtime.totalMemory()} gc=$gc allocated=$allocated"
    }

    private fun run(domain: String, size: Int, distribution: Int, output: File, diagnosticOnly: Boolean) {
        event("setup", resources())
        val generationStarted = System.nanoTime()
        val facts = rows(domain, size, distribution)
        event("generation-complete", "nanos=${System.nanoTime() - generationStarted} ${resources()}")
        File(output, "rows.tsv").printWriter().use { writer -> facts.forEach { writer.println(it) } }
        XdModel.hierarchy.clear()
        XdModel.registerNodes(Account, ServiceAccount, RobotAccount, CatalogItem, SystemItem, HiddenItem)
        val store = StaticStoreContainer.init(output, "typedFilterBenchmark")
        try {
            initMetaData(XdModel.hierarchy, store)
            val fixture = TypedFilterDataset(store, facts)
            store.transactional(readonly = true) {
                check(fixture.ids.values.toSet().size == size)
                fixture.rows.forEach { row ->
                    val entity = fixture.entities.getValue(row.key)
                    check(entity.entity.id == fixture.ids.getValue(row.key))
                    val expectedType = when (row.kind) {
                        TypedFilterDataset.Kind.ACCOUNT -> "Account"
                        TypedFilterDataset.Kind.SERVICE -> "ServiceAccount"
                        TypedFilterDataset.Kind.ROBOT -> "RobotAccount"
                        TypedFilterDataset.Kind.CATALOG -> "CatalogItem"
                        TypedFilterDataset.Kind.SYSTEM -> "SystemItem"
                        TypedFilterDataset.Kind.HIDDEN -> "HiddenItem"
                    }
                    check(entity.entity.type == expectedType)
                    when (entity) {
                        is Account -> check(entity.rowKey == row.key && entity.handle == row.text && entity.lastSeenAt == row.lastSeen)
                        is CatalogItem -> check(entity.rowKey == row.key && entity.caption == row.text)
                    }
                }
                val actual = if (domain == "account") Account.all().toIdList() else CatalogItem.all().toIdList()
                check(actual.toSet() == fixture.ids.values.toSet() && actual.size == size)
                val tx = store.persistentStore.currentTransaction as YTDBStoreTransactionImpl
                val schema = tx.activeYtdbSession().metadata.schema
                File(output, "schema.txt").writeText(schema.classes.joinToString("\n") {
                    "${it.name} parents=${it.superClassesNames}"
                } + "\nindexes=${schema.indexes}\n")
            }
            val expected = if (domain == "account") fixture.accountPage("a", true) else fixture.catalogPage(true)
            File(output, "identities.tsv").printWriter().use { writer ->
                facts.forEach { writer.println("${it.key}\t${fixture.ids.getValue(it.key)}\t${fixture.rid(it.key)}") }
            }
            File(output, "expected.txt").writeText(expected.joinToString("\n"))
            val matching = facts.filter { it.text?.startsWith("a", ignoreCase = true) == true }
            event("audit", "digest=${fixture.digest} rows=$size kinds=${facts.groupingBy { it.kind }.eachCount()} matches=${matching.size} excludedMatches=${matching.count { it.kind != TypedFilterDataset.Kind.ACCOUNT }} expected=$expected")
            event("setup-complete", resources())
            if (!diagnosticOnly) repeat(10) { iteration ->
                check(store.threadSession == null)
                event("before-operation", resources())
                acknowledged(if (iteration < 3) "warmup-$iteration" else "sample-${iteration - 3}")
                // Primary interval includes construction, disposal and transaction exit, not validation or probes.
                val started = System.nanoTime()
                val actual = store.transactional(readonly = true) { query(domain).toIdList() }
                val elapsed = System.nanoTime() - started
                event("operation-complete", "nanos=$elapsed ids=$actual")
                check(store.threadSession == null)
                check(actual == expected) { "Ordered identity mismatch: expected=$expected actual=$actual" }
                event("validated", resources())
            }
            check(store.threadSession == null)
            acknowledged("diagnostic-control")
            val control = store.transactional(readonly = true) { query(domain).toIdList() }
            event("operation-complete", "diagnostic-control")
            check(control == expected)
            event("validated", resources())
            acknowledged("diagnostic-observed")
            val evidence = RecursiveEvidence(output)
            val observed = store.transactional(readonly = true) {
                val low = (query(domain).entityIterable as EntityIterable).unwrap() as YTDBEntityIterable
                val traversal = low.traversal()
                File(output, "logical-query.txt").writeText("${low.query}\n$traversal\n")
                val tx = store.persistentStore.currentTransaction as YTDBStoreTransactionImpl
                val graphTx = (tx.g().graph as YTDBGraphInternal).tx()
                graphTx.withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT).withQueryListener(evidence)
                val rids = mutableListOf<String>()
                try {
                    evidence.install(traversal.asAdmin())
                    while (traversal.hasNext()) rids += traversal.next().id().toString()
                } finally {
                    traversal.close()
                    graphTx.withQueryListener(QueryMetricsListener.NO_OP)
                }
                rids
            }
            event("operation-complete", "diagnostic-observed")
            val expectedRids = expected.map { id -> fixture.rid(fixture.ids.entries.single { it.value == id }.key).toString() }
            check(observed == expectedRids) { "Observer changed ordered identities: $observed vs $expectedRids" }
            evidence.verify()
            event("validated", resources())
        } finally {
            event("cleanup", resources())
            try { store.close() } finally {
                try { StaticStoreContainer.dbProvider?.close() } finally { StaticStoreContainer.db?.close() }
            }
        }
        event("done", resources())
    }

    private class ObserverFailure(message: String) : IllegalStateException(message)

    /** Native candidates are source outputs, not physical record scans. MATCH is never inferred from request. */
    private class RecursiveEvidence(private val output: File) : QueryMetricsListener {
        private data class Source(val path: String, var opened: Int = 0, var candidates: Long = 0, var closed: Int = 0, var plans: Int = 0)
        private val sources = mutableListOf<Source>()
        private val failures = mutableListOf<String>()
        private val plans = mutableListOf<String>()
        private val notifications = mutableSetOf<Triple<Long, Long, String?>>()
        private var matched = 0

        fun install(root: Traversal.Admin<*, *>) {
            root.applyStrategies()
            val seen = Collections.newSetFromMap(IdentityHashMap<Traversal.Admin<*, *>, Boolean>())
            fun walk(traversal: Traversal.Admin<*, *>, path: String) {
                if (!seen.add(traversal)) return
                File(output, "traversals.txt").appendText("$path $traversal\n")
                traversal.steps.forEachIndexed { index, step ->
                    val location = "$path/$index:${step.javaClass.simpleName}"
                    when (step) {
                        is AbstractMatchPlanStep<*, *> -> matched++
                        is YTDBGraphStep<*, *> -> observe(step, location)
                        is GraphStep<*, *> -> failures += "Unrecognized source $location"
                    }
                    if (step is TraversalParent) {
                        step.getLocalChildren<Any, Any>().forEachIndexed { i, child -> walk(child, "$location/local-$i") }
                        step.getGlobalChildren<Any, Any>().forEachIndexed { i, child -> walk(child, "$location/global-$i") }
                    }
                }
            }
            walk(root, "root")
        }

        private fun observe(step: YTDBGraphStep<*, *>, path: String) {
            val source = Source(path).also { sources += it }
            val field = GraphStep::class.java.getDeclaredField("iteratorSupplier").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val supplier = field.get(step) as Supplier<Iterator<YTDBVertex>>
            @Suppress("UNCHECKED_CAST")
            val graphStep = step as YTDBGraphStep<Any, YTDBVertex>
            graphStep.setIteratorSupplier {
                source.opened++
                val delegate = try { supplier.get() } catch (failure: Throwable) {
                    failures += "$path open failed: $failure"
                    throw failure
                }
                object : CloseableIterator<YTDBVertex> {
                    private var closed = false
                    override fun hasNext() = delegate.hasNext()
                    override fun next() = delegate.next().also { source.candidates++ }
                    override fun remove(): Unit = throw UnsupportedOperationException()
                    override fun close() {
                        if (!closed) {
                            closed = true
                            try {
                                // Copy while the result set is still live, for every opening, including child resets.
                                val plan = graphStep.lastExecutionPlan
                                    ?: throw ObserverFailure("Missing executed source plan at $path")
                                capture(plan, "$path/open-${source.opened}")
                                source.plans++
                            } catch (failure: Throwable) {
                                failures += "$path plan capture failed: $failure"
                            } finally {
                                try { CloseableIterator.closeIterator(delegate); source.closed++ }
                                catch (failure: Throwable) { failures += "$path close failed: $failure"; throw failure }
                            }
                        }
                    }
                }
            }
        }

        override fun queryFinished(details: QueryMetricsListener.QueryDetails, startedAtMillis: Long, executionTimeNanos: Long) {
            try {
                if (!notifications.add(Triple(startedAtMillis, executionTimeNanos, details.querySummary))) return
                val plan = details.executionPlan ?: throw ObserverFailure("Missing executed plan")
                capture(plan, "listener duration=$executionTimeNanos")
            } catch (failure: Throwable) {
                failures += "Listener failed: $failure"
            }
        }

        private fun capture(plan: ExecutionPlan, origin: String) {
                val text = StringBuilder("origin=$origin\n")
                val seen = Collections.newSetFromMap(IdentityHashMap<ExecutionStep, Boolean>())
                fun walk(steps: List<ExecutionStep>, path: String) {
                    steps.forEachIndexed { i, step ->
                        if (seen.add(step)) {
                            text.append("$path/$i ${step.javaClass.name}\n")
                            walk(step.subSteps, "$path/$i/steps")
                            (step as? ExecutionStepInternal)?.subExecutionPlans?.forEachIndexed { p, child ->
                                copyPlan(child, text, "$path/$i/plan-$p")
                                walk(child.steps, "$path/$i/plan-$p")
                            }
                        }
                    }
                }
                copyPlan(plan, text, "root")
                walk(plan.steps, "root")
                plans += text.toString()
                File(output, "plans.txt").appendText(text.toString())
        }

        private fun copyPlan(plan: ExecutionPlan, text: StringBuilder, path: String) {
            text.append("$path ${plan.prettyPrint(0, 2)}\n")
        }

        fun verify() {
            if (matched != 0) failures += "MATCH source work cannot be counted by native supplier hooks"
            if (sources.isEmpty() || sources.any { it.opened == 0 || it.opened != it.closed || it.plans != it.opened })
                failures += "Missing source execution, per-opening plan or cleanup"
            if (plans.isEmpty()) failures += "No executed plans"
            File(output, "work.txt").writeText("matchedSteps=$matched\n" + sources.joinToString("\n") + "\nfailures=$failures\n")
            if (failures.isNotEmpty()) throw ObserverFailure(failures.joinToString())
            event("diagnostics", "actualNativeSources=${sources.size} plans=${plans.size} sources=$sources")
        }
    }
}
