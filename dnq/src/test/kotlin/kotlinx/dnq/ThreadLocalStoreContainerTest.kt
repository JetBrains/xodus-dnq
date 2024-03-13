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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.toList
import kotlinx.dnq.store.container.ThreadLocalStoreContainer
import kotlinx.dnq.store.container.createTransientEntityStore
import kotlinx.dnq.util.initMetaData
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

class ThreadLocalStoreContainerTest {

    class XdMultiStoreEntity(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdMultiStoreEntity>(storeContainer = ThreadLocalStoreContainer)
    }

    val databaseHome: File by lazy {
        val buildDir = System.getProperty("exodus.tests.buildDirectory")
        try {
            if (buildDir != null) {
                Files.createTempDirectory(Paths.get(buildDir), "xodus-test").toFile()
            } else {
                println("Build directory is not set !!!")
                Files.createTempDirectory("xodus-test").toFile()
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    lateinit var store1: TransientEntityStore
    lateinit var store2: TransientEntityStore

    @Before
    fun setup() {
        XdModel.hierarchy.clear()
        XdModel.registerNodes(XdMultiStoreEntity)
        store1 = createStore("store1")
        store2 = createStore("store2")
    }

    @After
    fun tearDown() {
        store1.close()
        store2.close()
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

    private fun createStore(name: String): TransientEntityStoreImpl {
        val store = createTransientEntityStore(File(databaseHome, name), "testDB") { envCloseForcedly = true }
        initMetaData(XdModel.hierarchy, store)
        return store
    }

    @Test
    fun `it should be possible to use different stores`() {
        ThreadLocalStoreContainer.transactional(store1) {
            XdMultiStoreEntity.new()

            ThreadLocalStoreContainer.transactional(store2) {
                XdMultiStoreEntity.new()
                XdMultiStoreEntity.new()
            }
        }


        ThreadLocalStoreContainer.transactional(store1) {
            assertThat(XdMultiStoreEntity.all().toList()).hasSize(1)
            ThreadLocalStoreContainer.transactional(store2) {
                assertThat(XdMultiStoreEntity.all().toList()).hasSize(2)
            }
        }

    }
}
