package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import org.junit.Test
import kotlin.system.measureTimeMillis

class XdPerformanceTest : DBTest() {

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

    override fun registerEntityTypes() {
        XdModel.registerNode(XdUser)
    }

    @Test
    fun `estimate toXd`() {
        transactional {
            (1..1000).forEach {
                XdUser.new()
            }
        }

        val millis = transactional {
            val users = XdUser.all().entityIterable.toList()
            measureTimeMillis {
                for (i in 1..100_000) {
                    for (entity in users) {
                        entity.toXd<XdUser>()
                    }
                }
            }
        }
        println(millis)
    }
}