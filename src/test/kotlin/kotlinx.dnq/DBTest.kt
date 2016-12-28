package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
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

        val groups by xdLink0_N(Group::users, onDelete = CLEAR, onTargetDelete = CLEAR)
    }

    abstract class Group(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<Group>()

        var name by xdRequiredStringProp(unique = true)
        val nestedGroups by xdLink0_N(NestedGroup::parentGroup)
        val users: XdMutableQuery<User> by xdLink0_N(User::groups, onDelete = CLEAR, onTargetDelete = CLEAR)
    }

    class NestedGroup(entity: Entity) : Group(entity) {
        companion object : XdNaturalEntityType<NestedGroup>()

        val parentGroup: Group by xdLink1(Group::nestedGroups)
    }

    class RootGroup(entity: Entity) : Group(entity) {
        companion object : XdNaturalEntityType<RootGroup>()
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
        XdModel.registerNode(User)
        XdModel.registerNode(RootGroup)
        XdModel.registerNode(NestedGroup)
        XdModel.registerNode(Image)
        XdModel.registerNode(Contact)
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
}