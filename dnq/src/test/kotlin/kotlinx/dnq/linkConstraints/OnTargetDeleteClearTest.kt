/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package kotlinx.dnq.linkConstraints

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.first
import kotlinx.dnq.query.toList
import mu.KLogging
import org.junit.Ignore
import org.junit.Test
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnTargetDeleteClearTest : DBTest() {
    companion object : KLogging()

    override fun registerEntityTypes() {
        XdModel.registerNodes(CLicense, CApplication, DParent, DChild, EApplication, ELicense)
    }


    class CLicense(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CLicense>()

        var application: CApplication by xdLink1(CApplication::license)
    }

    class CApplication(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CApplication>()

        var license by xdLink0_1(CLicense::application, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    @Test
    fun `onTargetDelete=CLEAR for single bidirectional link`() {
        val license = transactional {
            val application = CApplication.new()
            val license = CLicense.new()
            application.license = license
            license
        }
        transactional { license.delete() }
        transactional {
            assertThat(CLicense.all().toList()).isEmpty()
            assertThat(CApplication.all().toList()).hasSize(1)
            assertThat(CApplication.all().first().license).isNull()
        }
    }


    class DParent(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<DParent>()

        val children by xdLink0_N(DChild, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    class DChild(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<DChild>()

        var name by xdStringProp()
    }

    @Test
    fun `onTargetDelete=CLEAR for multiple link`() {
        val (parent, firstChild) = transactional {
            val parent = DParent.new()
            DChild.new { name = "Orphan" }

            val firstChild = DChild.new { name = "First" }
            val secondChild = DChild.new { name = "Second" }
            parent.children.add(firstChild)
            parent.children.add(secondChild)

            Pair(parent, firstChild)
        }
        transactional { firstChild.delete() }
        transactional {
            assertThat(DParent.all().toList()).hasSize(1)
            assertThat(DChild.all().toList()).hasSize(2)
            assertThat(parent.children.toList()).hasSize(1)
        }
    }

    class EApplication(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<EApplication>()

        var license by xdLink0_1(ELicense, onTargetDelete = OnDeletePolicy.CLEAR)
        var fallbackLicense by xdLink0_1(ELicense, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    class ELicense(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ELicense>()
    }

    @Test
    fun `onTargetDelete=CLEAR should not clear property if its value was changed before the deletion`() {
        val (application, oldLicense, newLicense) = transactional {
            val application = EApplication.new()
            val initialLicense = ELicense.new()
            application.license = initialLicense
            val newLicense = ELicense.new()
            Triple(application, initialLicense, newLicense)
        }
        transactional {
            application.license = newLicense
            oldLicense.delete()
        }
        transactional {
            assertThat(application.license).isEqualTo(newLicense)
        }
    }

    @Ignore
    @Test
    fun `onTargetDelete=CLEAR concurrently`() {
        val rnd = Random(777)
        (1..15).forEach { iteration ->
            fuzzyTest(rnd.nextInt(), iteration)
        }
    }

    private fun fuzzyTest(seed: Int, iteration: Int) {
        logger.info { "seed = [$seed], iteration = [$iteration]" }
        val apps = (1..1000).map {
            transactional {
                EApplication.new().apply {
                    license = ELicense.new()
                    fallbackLicense = ELicense.new()
                }
            }
        }.shuffled(Random(seed.toLong()))
        val errors = AtomicLong()
        apps.forEach { app ->
            val sema = Semaphore(0)
            val latch = Semaphore(0)
            thread {
                try {
                    transactional {
                        sema.acquire()
                        app.license!!.delete()
                        Thread.yield()
                    }
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                    logger.warn(t) { "License for app ${app.entityId}" }
                } finally {
                    latch.release()
                }
            }
            thread {
                try {
                    transactional {
                        sema.acquire()
                        app.fallbackLicense!!.delete()
                        Thread.yield()
                    }
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                    logger.warn(t) { "Fallback license for app ${app.entityId}" }
                } finally {
                    latch.release()
                }
            }
            sema.release(2)
            latch.acquire(2)
            assertEquals(0, errors.get())
            transactional {
                assertNull(app.license)
                assertNull(app.fallbackLicense)
            }
        }
        transactional {
            apps.forEach { it.delete() }
        }
        asyncProcessor.waitForJobs(100)
        tearDown()
        setup()
    }

    @Test
    fun `onTargetDelete=CLEAR should not clear property on target deletion`() {
        val (application, license) = transactional {
            val license = ELicense.new()
            val application = EApplication.new {
            }
            application.license = license
            Pair(application, license)
        }
        transactional {
            license.delete()
        }
        transactional {
            assertThat(application.license).isNull()
        }
    }
}
