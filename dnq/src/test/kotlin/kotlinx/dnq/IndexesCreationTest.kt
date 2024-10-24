package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
import mu.KLogging
import org.junit.Test

class IndexesCreationTest : DBTest() {

    companion object: KLogging()

    class XdUserDashboard(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdUserDashboard>("JPUserDashboard") {
            override val compositeIndices = listOf(
                listOf(XdUserDashboard::user, XdUserDashboard::reference)
            )
        }

        var reference: XdDashboard by xdParent(XdDashboard::userDashboards)
        var user: XdBaseUser by xdLink1(XdBaseUser::dashboards, onDelete = CLEAR, onTargetDelete = CASCADE)
    }

    class XdDashboard(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdDashboard>("JPDashboard")
        var name by xdRequiredStringProp()
        val userDashboards by xdChildren0_N(XdUserDashboard::reference)
    }

    abstract class XdBaseUser(entity: Entity) : XdEntity(entity) {
        companion object :
            XdNaturalEntityType<XdBaseUser>("JPBaseUser")
        var login by xdRequiredStringProp(unique = true, trimmed = true)
        val dashboards by xdLink0_N(XdUserDashboard::user)
    }


    class XdUser(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdUser>("JPUser")
    }

    class XdGuestUser(entity: Entity) : XdBaseUser(entity) {
        companion object : XdNaturalEntityType<XdGuestUser>("JPGuestUser")
    }


    override fun registerEntityTypes() {
        XdModel.registerNodes(XdUserDashboard, XdDashboard, XdBaseUser, XdUser, XdGuestUser)
    }

    @Test
    fun `Test composite indexes with inheritance creation`(){
        logger.info("If you see this message indexes genreation does work")
    }
}
