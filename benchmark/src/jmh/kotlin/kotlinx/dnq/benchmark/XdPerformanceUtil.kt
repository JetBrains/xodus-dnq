package kotlinx.dnq.benchmark

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.core.execution.DelegatingJobProcessor
import jetbrains.exodus.core.execution.JobProcessor
import jetbrains.exodus.core.execution.ThreadJobProcessorPool
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EventsMultiplexer
import kotlinx.dnq.*
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.util.initMetaData
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

open class XdPerformanceUtil {
    lateinit var databaseHome: File
    lateinit var store: TransientEntityStoreImpl

    class XdUser(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdUser>()

        var f by xdStringProp()
        var o by xdIntProp()
        var r by xdDateTimeProp()
        var k by xdDateTimeProp()

        var y by xdStringProp()
        var o_ by xdIntProp()
        var u by xdDateTimeProp()

        var lead: XdUser? by xdLink0_1(XdUser::team)
        val team by xdLink0_N(XdUser::lead)
    }


    fun initDatabase() {
        XdModel.hierarchy.clear()
        XdModel.registerNode(XdUser)
        databaseHome = File(System.getProperty("java.io.tmpdir"), "kotlinx.dnq.test")
        store = StaticStoreContainer.init(databaseHome, "testDB") {
            envCloseForcedly = true
        }

        initMetaData(XdModel.hierarchy, store)

        val eventsMultiplexer = EventsMultiplexer(createAsyncProcessor().apply(JobProcessor::start))
        store.eventsMultiplexer = eventsMultiplexer
        store.addListener(eventsMultiplexer)
    }

    protected fun createAsyncProcessor(): JobProcessor {
        return DelegatingJobProcessor(ThreadJobProcessorPool.getOrCreateJobProcessor("events"))
    }

    fun closeDatabase() {
        store.close()
        store.persistentStore.close()
        cleanUpDbDir()
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

    fun createUser(): Entity {
        return store.transactional {
            XdUser.new()
        }.entity
    }
}