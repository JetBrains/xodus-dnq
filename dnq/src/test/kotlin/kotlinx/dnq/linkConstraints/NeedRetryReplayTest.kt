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
package kotlinx.dnq.linkConstraints

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityId
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.filter
import kotlinx.dnq.query.first
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.query.toList
import mu.KLogging
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

/**
 * XD-1286: a flush that loses an MVCC race (NeedRetryException) replays its queued changes.
 * If one of those queued changes is the `onTargetDelete = CLEAR` clean-up for a link whose
 * SOURCE was created in the very same transaction, the replay crashes with
 * EntityRemovedInDatabaseException naming the source entity type: the queued closure captured a
 * duplicate TransientEntityImpl wrapper that still holds the dead temporary RID, while only the
 * canonical wrapper in `changedEntities` was rebound by `resetIfNew()`.
 *
 * Tests in this class:
 *  1. `baseline ... same transaction` — non-concurrent canary for the DIRECTED link. Must pass
 *     today; proves the CLEAR constraint's incoming-link query (ConstraintsUtil.kt:185-188) sees
 *     an edge created earlier in the same, still-uncommitted transaction. Without that
 *     read-your-writes property tests 2/3 would be silent false negatives.
 *  2. `XD-1286 ... setToOne` — Shape A reproduction, directed `..1` link, clear routed through
 *     `TransientEntitiesUpdaterImpl.setToOne` (:260-272, unguarded `source.getLink` at :263).
 *  3. `baseline ... bidirectional` + `XD-1286 ... setOneToOne` — Shape D, generality control:
 *     bidirectional `..1`/`..1` link, clear routed through `UndirectedAssociationSemantics` into
 *     `TransientEntitiesUpdaterImpl.setOneToOne` (:335-359, unguarded `e1.getLink` at :341).
 *     Answers whether the defect is `setToOne`-specific or generic across updater methods.
 *  4. `XD-1286 ... DataIntegrityViolationException` — probe of the recovery branch at
 *     TransientSessionImpl.kt:290-297 (`revert()` + `replayChanges()` with NO
 *     resetIfNew/generateIdIfNew pass), reached via a post-replay constraint violation.
 *
 * Tests 2, 3 and 4 assert the POST-FIX behaviour and are therefore expected to FAIL until
 * XD-1286 is fixed. They are deliberately not @Ignore'd — the failures document the defect.
 */
class NeedRetryReplayTest : DBTest() {
    companion object : KLogging() {
        /** Logger that emits the "Replaying changes" debug line. */
        const val TRANSIENT_STORE_LOGGER = "com.jetbrains.teamsys.dnq.database.TransientSessionImpl"
        const val REPLAY_MARKER = "Replaying changes"

        /** Logged by the `catch (exception: Throwable)` branch that owns the DIV recovery path. */
        const val FLUSH_CATCH_MARKER = "Catch exception in flush"

        /**
         * Shape E fallback switch: `false` means contention on the shared prototype records alone
         * is enough to force the NeedRetryException, `true` means the artificial shared counter is
         * needed. Determined empirically — see the test's KDoc.
         */
        const val SHAPE_E_NEEDS_COUNTER = false
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(
            RetryCounter,
            RLicense, RApplication,
            FLicense, FApplication,
            GLicense, GApplication,
            EPrototype, EUser, EProfile, EAttribute, EContact
        )
    }

    /** Shared entity both transactions write to, to force a deterministic write-write conflict. */
    class RetryCounter(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<RetryCounter>()

        var value by xdRequiredIntProp()
    }

    // --- directed ..1 link with CLEAR (Shape A, routed through setToOne) ---

    class RLicense(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<RLicense>()

        var name by xdStringProp()
    }

    class RApplication(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<RApplication>()

        var name by xdStringProp()
        var license by xdLink0_1(RLicense, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    // --- directed ..1 link with FAIL, used as positive proof / for the DIV probe ---

    class FLicense(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FLicense>()
    }

    class FApplication(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FApplication>()

        var license by xdLink0_1(FLicense, onTargetDelete = OnDeletePolicy.FAIL)
    }

    // --- bidirectional ..1/..1 link with CLEAR (Shape D, routed through setOneToOne) ---

    class GLicense(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<GLicense>()

        var name by xdStringProp()
        var application: GApplication? by xdLink0_1(GApplication::license)
    }

    class GApplication(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<GApplication>()

        var name by xdStringProp()
        var license by xdLink0_1(GLicense::application, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    // --- Shape E: HUB-13293 model. No deletes anywhere; a small graph of NEW entities per
    // --- transaction, each attribute pointing at a SHARED pre-existing prototype record.

    /** The shared, pre-existing record every competing transaction links to (cf. XdProfileAttributePrototype). */
    class EPrototype(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<EPrototype>()

        var name by xdRequiredStringProp()
        val attributes: XdMutableQuery<EAttribute> by xdLink0_N(EAttribute::prototype)
    }

    /** cf. JPUser */
    class EUser(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<EUser>()

        var login by xdRequiredStringProp()
        var profile: EProfile? by xdLink0_1(EProfile::user)
        val contacts: XdMutableQuery<EContact> by xdLink0_N(EContact::user)
    }

    /** cf. JPUserProfile — the entity HUB-13293 reports as "was removed". */
    class EProfile(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<EProfile>()

        var nickname by xdRequiredStringProp()
        var user: EUser? by xdLink0_1(EUser::profile)
        val attributes: XdMutableQuery<EAttribute> by xdLink0_N(EAttribute::profile)
    }

    /** cf. a profile attribute value bound to a shared prototype. */
    class EAttribute(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<EAttribute>()

        var value by xdRequiredStringProp()
        var profile: EProfile by xdLink1(EProfile::attributes)
        var prototype: EPrototype by xdLink1(EPrototype::attributes)
    }

    /** cf. JPEmailContact */
    class EContact(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<EContact>()

        var email by xdRequiredStringProp()
        var user: EUser by xdLink1(EUser::contacts)
    }

    /**
     * TEST 1 — baseline canary for the DIRECTED link, no concurrency. MUST PASS today.
     *
     * Source entity is created inside the same transaction that deletes the (previously
     * committed) target. The CLEAR constraint must find that source and null out its link.
     */
    @Test
    fun `baseline — onTargetDelete=CLEAR finds a source created in the same transaction`() {
        // --- part 1: CLEAR must null the link of a same-transaction-created source ---
        val license = transactional { RLicense.new { name = "prior-committed-license" } }

        val application = transactional {
            val app = RApplication.new { name = "same-tx-source" }
            app.license = license
            assertThat(app.license).isEqualTo(license)
            license.delete()
            // the clear runs eagerly, inside the very same transaction
            assertThat(app.license).isNull()
            app
        }

        transactional {
            // the source survived the target deletion ...
            assertThat(RApplication.all().toList()).hasSize(1)
            assertThat(application.isRemoved).isFalse()
            // ... its link was cleared ...
            assertThat(application.license).isNull()
            // ... and the target is really gone.
            assertThat(RLicense.all().toList()).isEmpty()
        }

        // --- part 2: positive proof that the incoming-link query really SAW that source ---
        // With onTargetDelete = FAIL the constraint can only fail if the query found the
        // same-transaction-created source. A silent "found nothing" would commit cleanly.
        val fLicense = transactional { FLicense.new() }
        val e = assertFailsWith<ConstraintsValidationException> {
            transactional {
                val app = FApplication.new()
                app.license = fLicense
                fLicense.delete()
            }
        }
        logger.info { "FAIL-policy probe reported: ${e.message}" }
        transactional {
            // nothing was committed by the failed transaction
            assertThat(FApplication.all().toList()).isEmpty()
            assertThat(FLicense.all().toList()).hasSize(1)
        }
    }

    /**
     * TEST 2 — XD-1286 reproduction, Shape A (directed link => `setToOne`).
     *
     * Losing transaction: bump shared counter, create source, link it to a pre-existing target,
     * delete the target (queues `setToOne(source, "license", null)`), then flush after the
     * winning transaction has already committed => NeedRetryException => replayChanges().
     */
    @Test
    fun `XD-1286 — directed onTargetDelete=CLEAR (setToOne) must survive flush replay`() {
        val license = transactional { RLicense.new { name = "prior-committed-license" } }

        val outcome = raceWithReplay {
            val app = RApplication.new { name = "same-tx-source" }
            app.license = license
            license.delete()
        }

        outcome.report("directed / setToOne")
        outcome.failIfLoserFailed()
        outcome.assertReplayHappened()

        transactional {
            // Both transactions bumped the shared counter, but a replay re-applies the
            // *captured* value (TransientEntitiesUpdaterImpl.setProperty queues the new value,
            // it never re-reads and re-increments), so the loser overwrites the winner's 1
            // with its own 1. That lost update is inherent to DNQ's value-replay, not part of
            // XD-1286 — the bump exists only to force the write-write conflict. Verified with
            // a counter-only race against unmodified production code: final value is 1.
            assertThat(counterValue()).isEqualTo(1)
            // the source created in the losing transaction survived the replay ...
            assertThat(RApplication.all().toList()).hasSize(1)
            // ... its link was cleared ...
            assertThat(RApplication.all().first().license).isNull()
            // ... and the target is gone.
            assertThat(RLicense.all().toList()).isEmpty()
        }
    }

    /**
     * TEST 3a — baseline canary for the BIDIRECTIONAL link, no concurrency. MUST PASS today.
     */
    @Test
    fun `baseline — bidirectional onTargetDelete=CLEAR finds a source created in the same transaction`() {
        val license = transactional { GLicense.new { name = "prior-committed-license" } }

        val application = transactional {
            val app = GApplication.new { name = "same-tx-source" }
            app.license = license
            assertThat(app.license).isEqualTo(license)
            // both ends are maintained by DNQ
            assertThat(license.application).isEqualTo(app)
            license.delete()
            // the clear runs eagerly, inside the very same transaction
            assertThat(app.license).isNull()
            app
        }

        transactional {
            assertThat(GApplication.all().toList()).hasSize(1)
            assertThat(application.isRemoved).isFalse()
            assertThat(application.license).isNull()
            assertThat(GLicense.all().toList()).isEmpty()
        }
    }

    /**
     * TEST 3b — XD-1286 generality control, Shape D (bidirectional link => `setOneToOne`).
     *
     * Identical scenario to test 2, but the CLEAR is routed through
     * `UndirectedAssociationSemantics.setOneToOne` => `TransientEntitiesUpdaterImpl.setOneToOne`,
     * whose `e1.getLink(...)` at :341 is unguarded in exactly the same way as `setToOne`:341.
     */
    @Test
    fun `XD-1286 — bidirectional onTargetDelete=CLEAR (setOneToOne) must survive flush replay`() {
        val license = transactional { GLicense.new { name = "prior-committed-license" } }

        val outcome = raceWithReplay {
            val app = GApplication.new { name = "same-tx-source" }
            app.license = license
            license.delete()
        }

        outcome.report("bidirectional / setOneToOne")
        outcome.failIfLoserFailed()
        outcome.assertReplayHappened()

        transactional {
            // Both transactions bumped the shared counter, but a replay re-applies the
            // *captured* value (TransientEntitiesUpdaterImpl.setProperty queues the new value,
            // it never re-reads and re-increments), so the loser overwrites the winner's 1
            // with its own 1. That lost update is inherent to DNQ's value-replay, not part of
            // XD-1286 — the bump exists only to force the write-write conflict. Verified with
            // a counter-only race against unmodified production code: final value is 1.
            assertThat(counterValue()).isEqualTo(1)
            assertThat(GApplication.all().toList()).hasSize(1)
            assertThat(GApplication.all().first().license).isNull()
            assertThat(GLicense.all().toList()).isEmpty()
        }
    }

    /**
     * TEST 4 — probe of the DataIntegrityViolationException recovery branch
     * (TransientSessionImpl.kt:290-297).
     *
     * That branch does `transactionInternal.revert()` + `replayChanges()` with NO
     * resetIfNew/generateIdIfNew pass, so every entity created in the transaction should end up
     * with a stale vertex. Reaching it requires an exception from inside the retry loop that is a
     * DataIntegrityViolationException — i.e. a constraint violation detected by the POST-REPLAY
     * `checkBeforeSaveChangesConstraints()` (the pre-loop check is outside the guarded block).
     *
     * Scenario: the losing transaction deletes a FAIL-policy target that has no incoming links in
     * its own snapshot, and creates an unrelated entity. The winning transaction concurrently adds
     * an incoming FAIL link to that very target and commits first. The loser then flushes:
     *   - conflict on the shared counter => NeedRetryException => replay in a fresh transaction,
     *   - post-replay `checkIncomingLinks` now sees the winner's incoming link
     *     => ConstraintsValidationException (a DataIntegrityViolationException)
     *   - => `revert()` + `replayChanges()` with stale vertices.
     *
     * Expected POST-FIX behaviour (asserted): the caller sees the REAL constraint error
     * (ConstraintsValidationException). Today the review predicts the replay itself blows up and
     * masks it with EntityRemovedInDatabaseException, and the original DIV is never rethrown.
     */
    @Test
    fun `XD-1286 — DataIntegrityViolation recovery after replay must not be masked`() {
        val target = transactional { FLicense.new() }

        val outcome = raceWithReplay(
            winnerWork = {
                // becomes visible to the loser only in its post-conflict transaction
                FApplication.new { license = target }
            },
            loserWork = {
                RLicense.new { name = "created-in-losing-tx" }
                target.delete()
            }
        )

        outcome.report("DIV recovery path")
        logger.info { "flush-catch lines: ${outcome.lines.filter { FLUSH_CATCH_MARKER in it }}" }

        // The premise: a replay must have happened, and the retry loop must have thrown into the
        // `catch (exception: Throwable)` branch that owns the DIV recovery code.
        outcome.assertReplayHappened()
        if (outcome.captureEnabled) {
            assertThat(outcome.lines.filter { FLUSH_CATCH_MARKER in it }).isNotEmpty()
        }

        val error = outcome.loserError
        logger.info { "loser outcome: ${error?.let { "${it::class.java.name}: ${it.message}" } ?: "committed"}" }
        error?.let { logger.error(it) { "XD-1286 DIV probe: losing transaction failed" } }

        // Deleting the target IS illegal (the winner holds a FAIL-policy link to it), so the
        // transaction must fail — but with the real constraint error, not a stale-vertex error.
        assertThat(error).isNotNull()
        if (error is EntityRemovedInDatabaseException) {
            throw AssertionError(
                "XD-1286: the DataIntegrityViolationException recovery path masked the real " +
                        "constraint error with a stale-vertex failure: " +
                        "${error::class.java.name}: ${error.message}\n${error.stackTraceText()}",
                error
            )
        }
        assertThat(error).isInstanceOf(ConstraintsValidationException::class.java)
    }

    /**
     * TEST 5 — Shape E: is HUB-13293 the same defect as XD-1286?
     *
     * HUB-13293: concurrent `POST /api/rest/users` requests each CREATE a small object graph
     * (JPUser + JPUserProfile + JPEmailContact) and write `profile.attributes`, where every
     * attribute links to a SHARED pre-existing prototype record. Concurrency alone does not
     * reproduce, writing attributes alone does not reproduce — only both together. The failure is
     * `Catch exception in flush: JPUserProfile[6-7062] was removed.` naming an entity the request
     * just created, and one occurrence surfaced the raw `The record with id '#1081:394' was not
     * found`. There is NO entity deletion anywhere in that scenario, hence no on-target-delete
     * CLEAR — which is what makes it a different-looking symptom from XD-1286.
     *
     * This probe reproduces that shape with no deletes at all: both transactions create a fresh
     * user graph and link its attributes to the same shared prototypes. The contention on the
     * shared prototype records (their link bags are modified by both transactions) is expected to
     * produce the NeedRetryException by itself — [SHAPE_E_NEEDS_COUNTER] records whether that was
     * in fact enough or whether the artificial shared counter had to be used as a fallback.
     *
     * MEASURED RESULT: this shape does NOT reproduce — the losing transaction replays and commits
     * cleanly. The `profile wrapper identity` diagnostic in [createUserGraph] shows why: inside the
     * creating transaction BOTH a link traversal and a query return the *same* TransientEntity
     * object as `EProfile.new` did, so there is no second wrapper to be left behind by
     * `resetIfNew()`. Three variants were tried and all pass: canonical wrappers only, re-read via
     * link, re-read via query. So on this evidence HUB-13293 is not the XD-1286 duplicate-wrapper
     * defect; some further ingredient of the Hub scenario is missing from this model.
     *
     * The test asserts the correct behaviour (the losing transaction commits and its whole graph
     * is intact) and is expected to keep PASSING; it is kept as a guard for that shape.
     */
    @Test
    fun `XD-1286 — HUB-13293 shape, concurrent creation linking a shared prototype, no deletes`() {
        val prototypeNames = listOf("proto-a", "proto-b")
        transactional { prototypeNames.forEach { protoName -> EPrototype.new { name = protoName } } }

        val outcome = raceWithReplay(
            bumpCounter = SHAPE_E_NEEDS_COUNTER,
            winnerWork = { createUserGraph("winner", prototypeNames) },
            loserWork = { createUserGraph("loser", prototypeNames) }
        )

        outcome.report("HUB-13293 shape / no deletes")
        logger.info { "flush-catch lines: ${outcome.lines.filter { FLUSH_CATCH_MARKER in it }}" }
        outcome.failIfLoserFailed()
        outcome.assertReplayHappened()

        transactional {
            // both graphs must be intact
            listOf("winner", "loser").forEach { tag ->
                val user = EUser.all().filter { it.login eq "$tag-login" }.firstOrNull()
                assertThat(user).isNotNull()
                assertThat(user!!.contacts.toList().map { it.email }).containsExactly("$tag@example.com")
                val profile = user.profile
                assertThat(profile).isNotNull()
                assertThat(profile!!.nickname).isEqualTo("$tag-nickname-updated")
                assertThat(profile.attributes.toList().map { it.value })
                    .containsExactlyElementsIn(prototypeNames.map { "$tag-$it" })
                assertThat(profile.attributes.toList().map { it.prototype.name })
                    .containsExactlyElementsIn(prototypeNames)
            }
            // the shared prototypes must have collected the attributes of BOTH transactions
            prototypeNames.forEach { protoName ->
                val prototype = EPrototype.all().filter { it.name eq protoName }.first()
                assertThat(prototype.attributes.toList().map { it.value })
                    .containsExactly("winner-$protoName", "loser-$protoName")
            }
        }
    }

    /** Creates the HUB-13293 object graph: user + profile + contact + one attribute per prototype. */
    private fun createUserGraph(tag: String, prototypeNames: List<String>) {
        val user = EUser.new { login = "$tag-login" }
        val createdProfile = EProfile.new { nickname = "$tag-nickname" }
        user.profile = createdProfile
        EContact.new {
            email = "$tag@example.com"
            this.user = user
        }

        // Re-read the profile the way a request handler would. Two idioms are exercised because
        // they differ in wrapper identity, which is what decides whether XD-1286 can bite:
        //  - a link traversal (`user.profile`) returns the very same TransientEntity object;
        //  - a QUERY returns a freshly built wrapper (PersistentEntityIterableWrapper ->
        //    TransientSessionImpl.newEntityImpl), which is exactly how ConstraintsUtil's
        //    incoming-link query produces the duplicate wrapper that XD-1286 trips over.
        // `resetIfNew()` mutates the YTDBEntity object in place (TransientEntityImpl.kt:57-61),
        // so a duplicate that still shares that object is healed for free; only a duplicate
        // holding a *different* persistent object with the dead RID stays broken.
        val viaLink = user.profile!!
        val profile = EProfile.all().filter { it.nickname eq "$tag-nickname" }.first()
        logger.info {
            val canonical = createdProfile.entity as TransientEntity
            val link = viaLink.entity as TransientEntity
            val queried = profile.entity as TransientEntity
            "[$tag] profile wrapper identity: via link same object = ${canonical === link} " +
                    "(persistent ${canonical.entity === link.entity}), " +
                    "via query same object = ${canonical === queried} " +
                    "(persistent ${canonical.entity === queried.entity}), " +
                    "ids ${canonical.id} / ${link.id} / ${queried.id}"
        }
        prototypeNames.forEach { protoName ->
            val prototype = EPrototype.all().filter { it.name eq protoName }.first()
            val attribute = EAttribute.new { value = "$tag-$protoName" }
            // "write profile.attributes" — the HUB-13293 trigger; both ends are collection writes
            profile.attributes.add(attribute)
            prototype.attributes.add(attribute)
        }
        // a property write queued after the link writes, on the re-read wrapper
        profile.nickname = "$tag-nickname-updated"
    }

    /**
     * TEST 6 — Shape F: the same HUB-13293 create-only scenario as test 5, but with the
     * `XdModel.toXdCache` lookup bypassed for one read.
     *
     * Test 5 passes only because every XdLink getter and XdQuery goes through `XdModel.toXd`
     * (XdModel.kt:152-177), which consults `toXdCache` — a store-global
     * `SoftConcurrentLongObjectCache` keyed on the RID (`cacheKey`, XdModel.kt:207; the
     * YTDBVertexEntity hash is RID-only). The persistent-layer iterators still mint a fresh
     * `TransientEntityImpl` per call, but the cache collapses them back onto the first-cached
     * wrapper — which is the canonical `.new` one that `resetIfNew()` heals.
     *
     * `toXdCache` is a SOFT cache: an entry may vanish at any moment under memory pressure. This
     * test simulates exactly that miss with the supported `ignoreXdCache` flag
     * (`XdQuery.firstOrNull(ignoreXdCache = true)`, XdQuery.kt:814) — no reflection, no production
     * change. If the defect is not specific to the on-target-delete CLEAR path, a cache miss on a
     * plain create must reproduce the very same replay crash with NO deletes anywhere.
     *
     * Asserts the POST-FIX behaviour, so it is expected to FAIL until XD-1286 is fixed.
     */
    @Test
    fun `XD-1286 — cache-miss variant of the HUB-13293 shape, no deletes, must survive flush replay`() {
        val prototypeNames = listOf("proto-a", "proto-b")
        transactional { prototypeNames.forEach { protoName -> EPrototype.new { name = protoName } } }

        val outcome = raceWithReplay(
            bumpCounter = SHAPE_E_NEEDS_COUNTER,
            winnerWork = { createUserGraphWithCacheMiss("winner", prototypeNames) },
            loserWork = { createUserGraphWithCacheMiss("loser", prototypeNames) }
        )

        outcome.report("cache-miss / no deletes")
        cacheMissWrappers.forEach { (tag, wrappers) ->
            logger.info { "[$tag] AFTER FAILURE ${identity("canonical", wrappers[0])}" }
            logger.info { "[$tag] AFTER FAILURE ${identity("uncached ", wrappers[1])}" }
        }
        outcome.failIfLoserFailed()
        outcome.assertReplayHappened()

        transactional {
            listOf("winner", "loser").forEach { tag ->
                val user = EUser.all().filter { it.login eq "$tag-login" }.firstOrNull()
                assertThat(user).isNotNull()
                val profile = user!!.profile
                assertThat(profile).isNotNull()
                assertThat(profile!!.nickname).isEqualTo("$tag-nickname-updated")
                assertThat(profile.attributes.toList().map { it.value })
                    .containsExactlyElementsIn(prototypeNames.map { "$tag-$it" })
            }
        }
    }

    /** tag -> [canonical wrapper, cache-bypassing wrapper], kept for post-mortem RID reporting. */
    private val cacheMissWrappers = ConcurrentHashMap<String, List<TransientEntity>>()

    /** Same graph as [createUserGraph], but the profile is re-read with the toXd cache bypassed. */
    private fun createUserGraphWithCacheMiss(tag: String, prototypeNames: List<String>) {
        val user = EUser.new { login = "$tag-login" }
        val createdProfile = EProfile.new { nickname = "$tag-nickname" }
        user.profile = createdProfile
        EContact.new {
            email = "$tag@example.com"
            this.user = user
        }

        // The one and only difference from test 5: simulate a soft-cache miss.
        val profile = EProfile.all().filter { it.nickname eq "$tag-nickname" }
            .firstOrNull(ignoreXdCache = true)!!

        cacheMissWrappers[tag] = listOf(createdProfile.entity as TransientEntity, profile.entity as TransientEntity)
        logger.info {
            "[$tag] BEFORE FLUSH ${identity("canonical", createdProfile.entity as TransientEntity)} | " +
                    identity("uncached ", profile.entity as TransientEntity) +
                    " | same wrapper = ${createdProfile.entity === profile.entity}"
        }

        prototypeNames.forEach { protoName ->
            val prototype = EPrototype.all().filter { it.name eq protoName }.first()
            val attribute = EAttribute.new { value = "$tag-$protoName" }
            profile.attributes.add(attribute)
            prototype.attributes.add(attribute)
        }
        profile.nickname = "$tag-nickname-updated"
    }

    /** Object identities and RIDs of a wrapper, for stale-vertex post-mortems. */
    private fun identity(label: String, te: TransientEntity): String {
        val persistent = runCatching { te.entity }.getOrNull()
        val vertexInfo = (persistent as? YTDBVertexEntity)?.let { pe ->
            val v = runCatching { pe.vertex }.getOrNull()
            "rawVertex#${v?.let { System.identityHashCode(it) }} " +
                    "rid=${runCatching { v?.id()?.toString() }.getOrElse { "<${it.javaClass.simpleName}>" }}"
        } ?: "rawVertex=<n/a>"
        val oid = runCatching { (te.id as? YTDBEntityId)?.asOId()?.toString() }.getOrElse { "<${it.javaClass.simpleName}>" }
        return "$label = wrapper#${System.identityHashCode(te)} " +
                "persistent#${persistent?.let { System.identityHashCode(it) }} id=${te.id} oid=$oid $vertexInfo"
    }

    // ------------------------------------------------------------------------------------------
    // test scaffolding
    // ------------------------------------------------------------------------------------------

    private fun counterValue() = RetryCounter.all().first().value

    /**
     * Runs [loserWork] in a transaction that is forced to lose an MVCC race and therefore replays
     * its changes. Ordering (all deterministic, no sleeps):
     *  1. both transactions start and take their snapshots (CyclicBarrier),
     *  2. both bump the shared [RetryCounter] — this guarantees the write-write conflict,
     *  3. the loser performs [loserWork] and signals,
     *  4. the winner performs [winnerWork] and commits first,
     *  5. the loser flushes => NeedRetryException => replayChanges().
     */
    private fun raceWithReplay(
        bumpCounter: Boolean = true,
        winnerWork: () -> Unit = {},
        loserWork: () -> Unit
    ): RaceOutcome {
        val counter = if (bumpCounter) {
            transactional { RetryCounter.new { value = 0 } }
            transactional { RetryCounter.all().first() }
        } else {
            null
        }

        val start = CyclicBarrier(2)
        val loserWorkDone = CountDownLatch(1)
        val winnerCommitted = CountDownLatch(1)
        var loserError: Throwable? = null
        var winnerError: Throwable? = null

        // `capture` patches a logger level and System.err, `pool` owns two threads: both must be
        // released on EVERY exit path, so each is acquired immediately before its own try/finally.
        val capture = DebugLogCapture(TRANSIENT_STORE_LOGGER)
        try {
            val captureEnabled = capture.enabled
            val pool = Executors.newFixedThreadPool(2)
            try {
                val winner = pool.submit {
                    try {
                        transactional {
                            start.await(120, TimeUnit.SECONDS)
                            counter?.let { it.value += 1 }
                            winnerWork()
                            // commit only after the loser has done its work in its own snapshot
                            if (!loserWorkDone.await(30, TimeUnit.SECONDS)) {
                                throw AssertionError("loserWorkDone latch timed out after 30s")
                            }
                        }
                    } catch (t: Throwable) {
                        winnerError = t
                    } finally {
                        winnerCommitted.countDown()
                    }
                }

                val loser = pool.submit {
                    try {
                        transactional {
                            start.await(120, TimeUnit.SECONDS)
                            counter?.let { it.value += 1 }
                            try {
                                loserWork()
                            } finally {
                                loserWorkDone.countDown()
                            }
                            // block until the winner has committed so that our flush must retry
                            if (!winnerCommitted.await(30, TimeUnit.SECONDS)) {
                                throw AssertionError("winnerCommitted latch timed out after 30s")
                            }
                        }
                    } catch (t: Throwable) {
                        loserError = t
                    } finally {
                        loserWorkDone.countDown()
                    }
                }

                listOf(winner, loser).forEach { it.get(120, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            return RaceOutcome(loserError, winnerError, capture.lines, captureEnabled)
        } finally {
            capture.close()
        }
    }

    private inner class RaceOutcome(
        val loserError: Throwable?,
        val winnerError: Throwable?,
        val lines: List<String>,
        val captureEnabled: Boolean
    ) {
        val replayLines: List<String> get() = lines.filter { REPLAY_MARKER in it }

        fun report(shape: String) {
            logger.info {
                "[$shape] log capture enabled = $captureEnabled, captured lines: ${lines.size}, " +
                        "replay lines: ${replayLines.size}"
            }
            replayLines.forEach { logger.info { "[$shape] REPLAY EVIDENCE: $it" } }
            assertThat(winnerError).isNull()
        }

        /** Guards against a meaningless green run: without a replay these tests prove nothing. */
        fun assertReplayHappened() {
            if (captureEnabled) {
                assertThat(replayLines).isNotEmpty()
            } else {
                throw AssertionError(
                    "Log capture failed to enable (reflection on slf4j-simple failed?) — " +
                            "cannot verify replay happened. A GREEN run without this check proves nothing."
                )
            }
        }

        fun failIfLoserFailed() {
            loserError?.let {
                logger.error(it) { "XD-1286: losing transaction failed during flush replay" }
                throw AssertionError(
                    "XD-1286 reproduced: losing transaction failed. " +
                            "Replay observed = ${replayLines.isNotEmpty()}. " +
                            "Exception: ${it::class.java.name}: ${it.message}\n${it.stackTraceText()}",
                    it
                )
            }
        }
    }

    private fun Throwable.stackTraceText(): String = StringWriter().also { sw ->
        PrintWriter(sw).use { printStackTrace(it) }
    }.toString()

    /**
     * Temporarily raises a single logger to DEBUG and tees System.err, so that the tests can prove
     * the flush actually replayed ("Replaying changes" from TransientSessionImpl).
     *
     * The effective SLF4J binding on the test classpath is slf4j-simple (log4j-slf4j-impl:2.17 is
     * an SLF4J-1.7 binding and is ignored by slf4j-api 2.x), and slf4j-simple has no runtime
     * reconfiguration API: the per-logger level is a private int field set at construction time.
     * We therefore patch that single field reflectively and restore it in [close], so no global
     * log level is changed and other tests are unaffected. If the field cannot be found (a
     * different binding), [enabled] stays false and the caller falls back to reporting.
     */
    private class DebugLogCapture(loggerName: String) : AutoCloseable {
        private val messages = Collections.synchronizedList(ArrayList<String>())
        private val originalErr: PrintStream = System.err
        private val restores = ArrayList<() -> Unit>()

        /** True if the target logger was really switched to DEBUG. */
        var enabled = false
            private set

        init {
            val logger = LoggerFactory.getLogger(loggerName)
            val field = generateSequence(logger.javaClass as Class<*>) { it.superclass }
                .mapNotNull { klass -> runCatching { klass.getDeclaredField("currentLogLevel") }.getOrNull() }
                .firstOrNull()
            if (field != null) {
                field.isAccessible = true
                val oldLevel = field.getInt(logger)
                field.setInt(logger, LOG_LEVEL_DEBUG)
                restores += { field.setInt(logger, oldLevel) }
                System.setErr(PrintStream(TeeStream(originalErr, messages), true))
                restores += { System.setErr(originalErr) }
                enabled = true
            }
        }

        val lines: List<String> get() = ArrayList(messages)

        override fun close() {
            restores.asReversed().forEach { runCatching { it() } }
            restores.clear()
            enabled = false
        }

        private class TeeStream(
            private val target: PrintStream,
            private val sink: MutableList<String>
        ) : OutputStream() {
            private val line = StringBuilder()

            @Synchronized
            override fun write(b: Int) {
                target.write(b)
                if (b == '\n'.code) {
                    sink.add(line.toString())
                    line.setLength(0)
                } else {
                    line.append(b.toChar())
                }
            }

            @Synchronized
            override fun flush() = target.flush()
        }

        companion object {
            /** org.slf4j.simple.SimpleLogger.LOG_LEVEL_DEBUG */
            private const val LOG_LEVEL_DEBUG = 10
        }
    }
}
