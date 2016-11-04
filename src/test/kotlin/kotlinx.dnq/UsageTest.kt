package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.query.query
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.util.initMetaData
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class UsageTest {

    class User(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<User>()

        var login by xdRequiredStringProp(unique = true)
    }

    lateinit var store: TransientEntityStoreImpl
    lateinit var databaseHome: File

    @Before
    fun setup() {
        XdModel.registerNode(User)

        databaseHome = File(System.getProperty("java.io.tmpdir"), "kotlinx.dnq.test")
        store = StaticStoreContainer.init(databaseHome, "testDB") {
            envCloseForcedly = true
        }

        initMetaData(XdModel.hierarchy, store)
    }

    @After
    fun tearDown() {
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


    @Test
    fun create() {
        val login = "mazine"

        store.transactional { txn ->
            User.new {
                this.login = login
            }
        }

        store.transactional {
            Assert.assertNotNull(User.query(User::login eq login).firstOrNull())
        }
    }
}