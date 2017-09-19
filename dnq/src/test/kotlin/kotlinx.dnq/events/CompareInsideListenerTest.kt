package kotlinx.dnq.events

import jetbrains.exodus.core.execution.JobProcessor
import jetbrains.exodus.entitystore.EventsMultiplexer
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CompareInsideListenerTest : DBTest() {

    @Before
    fun updateMultiplexer() {
        store.eventsMultiplexer = EventsMultiplexer(createAsyncProcessor().apply(JobProcessor::start))
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Bar)
    }

    @Test
    fun updateSync() {
        val called = AtomicBoolean(false)
        val b = transactional {
            Bar.new {
                bar = "bar"
            }
        }
        transactional {
            b.onUpdate { old, current ->
                assertTrue(b == b)
                assertTrue(old == current)
                assertTrue(current == b)
                assertTrue(b == current)
                assertTrue(current == current)
                assertTrue(old == b)
                assertTrue(b == old)
                assertTrue(old == old)
                called.set(true)
            }
        }
        transactional {
            b.bar = "bar1"
        }
        assertTrue(called.get())
    }
}
