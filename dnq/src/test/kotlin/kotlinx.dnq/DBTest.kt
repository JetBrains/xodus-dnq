/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.core.execution.DelegatingJobProcessor
import jetbrains.exodus.core.execution.JobProcessor
import jetbrains.exodus.core.execution.ThreadJobProcessorPool
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.QueryCancellingPolicy
import jetbrains.exodus.entitystore.Where
import jetbrains.exodus.entitystore.Where.*
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import kotlinx.dnq.listener.asLegacyListener
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.simple.email
import kotlinx.dnq.simple.min
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.util.initMetaData
import org.junit.After
import org.junit.Before
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

abstract class DBTest {
    lateinit var store: TransientEntityStoreImpl
    lateinit var databaseHome: File
    val typeListeners = mutableListOf<Pair<XdEntityType<*>, XdEntityListener<*>>>()
    val instanceListeners = mutableListOf<Pair<XdEntity, XdEntityListener<*>>>()

    class User(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<User>()

        var login by xdRequiredStringProp(trimmed = true, unique = true)
        var name by xdStringProp(dbName = "visibleName")
        var age by xdIntProp { this.min(0) }
        var skill by xdRequiredIntProp()
        var salary by xdLongProp()
        var isGuest by xdBooleanProp()
        var registered by xdDateTimeProp()
        val contacts by xdLink0_N(Contact::user)
        var supervisor by xdLink0_1(User, "boss")
        var isMale by xdNullableBooleanProp()

        val groups by xdLink0_N(Group::users, onDelete = CLEAR, onTargetDelete = CLEAR)
    }

    abstract class Group(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<Group>()

        var name by xdRequiredStringProp(unique = true)
        var alias by xdStringProp()
        val nestedGroups by xdLink0_N(NestedGroup::parentGroup, dbPropertyName = "nested", dbOppositePropertyName = "parent")
        val users: XdMutableQuery<User> by xdLink0_N(User::groups, onDelete = CLEAR, onTargetDelete = CLEAR)

        abstract var autoJoin: Boolean
        abstract val owner: User?
    }

    class NestedGroup(entity: Entity) : Group(entity) {
        companion object : XdNaturalEntityType<NestedGroup>()

        var parentGroup: Group by xdLink1(Group::nestedGroups, dbPropertyName = "parent", dbOppositePropertyName = "nested")
        override var autoJoin by xdBooleanProp()
        override var owner: User by xdLink1(User)
    }

    open class RootGroup(entity: Entity) : Group(entity) {
        companion object : XdNaturalEntityType<RootGroup>()

        override var autoJoin: Boolean
            get() = false
            set(value) {}

        override val owner: User? = null
    }

    class Image(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<Image>()

        var content by xdRequiredBlobStringProp()
    }

    class Contact(override val entity: Entity) : XdEntity() {

        companion object : XdNaturalEntityType<Contact>()

        var user: User by xdLink1(User::contacts)

        var email by xdRequiredStringProp() { email() }
    }

    class Team(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<Team>()

        var name by xdRequiredStringProp(unique = true)
        val fellows by xdChildren0_N(Fellow::team)
    }

    class Fellow(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<Fellow>()

        var name by xdRequiredStringProp()
        var team: Team by xdParent(Team::fellows)
    }


    @Before
    fun setup() {
        registerEntityTypes()
        databaseHome = File(System.getProperty("java.io.tmpdir"), "kotlinx.dnq.test")
        store = StaticStoreContainer.init(databaseHome, "testDB") {
            envCloseForcedly = true
        }

        initMetaData(XdModel.hierarchy, store)
    }

    open fun registerEntityTypes() {
        XdModel.registerNodes(User, RootGroup, NestedGroup, Image, Contact, Team, Fellow)
    }

    @After
    fun tearDown() {
        val eventsMultiplexer = store.eventsMultiplexer
        if (eventsMultiplexer != null) {
            typeListeners.forEach {
                eventsMultiplexer.removeListener(it.first.entityType, it.second.asLegacyListener())
            }
            instanceListeners.forEach {
                eventsMultiplexer.removeListener(it.first.entity, it.second.asLegacyListener())
            }
        }
        store.close()
        store.persistentStore.close()
        cleanUpDbDir()
    }

    protected fun createAsyncProcessor(): JobProcessor {
        return DelegatingJobProcessor(ThreadJobProcessorPool.getOrCreateJobProcessor("events"))
    }

    fun <T> transactional(
            readonly: Boolean = false,
            queryCancellingPolicy: QueryCancellingPolicy? = null,
            isNew: Boolean = false,
            block: (TransientStoreSession) -> T
    ) = store.transactional(readonly, queryCancellingPolicy, isNew, block)

    fun <XD : XdEntity> XdEntityType<XD>.onUpdate(mode: Where = SYNC_AFTER_FLUSH, action: (XD, XD) -> Unit): XdEntityListener<XD> {
        val listener = makeListener(mode, action)
        store.eventsMultiplexer.addListener(this, listener)
        typeListeners.add(this to listener)
        return listener
    }

    fun <XD : XdEntity> XD.onUpdate(mode: Where = SYNC_AFTER_FLUSH, action: (XD, XD) -> Unit): XdEntityListener<XD> {
        val listener = makeListener(mode, action)
        store.eventsMultiplexer.addListener(this, listener)
        instanceListeners.add(this to listener)
        return listener
    }

    private fun <XD : XdEntity> makeListener(mode: Where, action: (XD, XD) -> Unit): XdEntityListener<XD> {
        return when (mode) {
            SYNC_BEFORE_FLUSH_AFTER_CONSTRAINTS -> object : XdEntityListener<XD> {
                override fun updatedSyncAfterConstraints(old: XD, current: XD) = action(old, current)
            }
            SYNC_BEFORE_FLUSH_BEFORE_CONSTRAINTS -> object : XdEntityListener<XD> {
                override fun updatedSyncBeforeConstraints(old: XD, current: XD) = action(old, current)
            }
            SYNC_AFTER_FLUSH -> object : XdEntityListener<XD> {
                override fun updatedSync(old: XD, current: XD) = action(old, current)
            }
            ASYNC_AFTER_FLUSH -> object : XdEntityListener<XD> {
                override fun updatedAsync(old: XD, current: XD) = action(old, current)
            }
        }
    }

    private fun cleanUpDbDir() {
        if (databaseHome.exists() && databaseHome.isDirectory) {
            Files.walkFileTree(databaseHome.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, e: IOException) = handleException(e)

                private fun handleException(e: IOException): FileVisitResult {
                    e.printStackTrace() // replace with more robust error handling
                    return FileVisitResult.TERMINATE
                }

                override fun postVisitDirectory(dir: Path, e: IOException?): FileVisitResult {
                    if (e != null) return handleException(e)
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
