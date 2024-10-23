package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.XdMutableQuery
import mu.KLogging
import kotlin.test.Test

class LinkValidationTest : DBTest() {

    companion object : KLogging()

    class XdIdpGroup(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdIdpGroup>()

        var container by xdLink1(XdBaseIdpData::userGroups, onTargetDelete = OnDeletePolicy.CASCADE)
        var idpGroupId by xdRequiredStringProp()
    }

    abstract class XdBaseIdpData(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdBaseIdpData>()

        val userGroups: XdMutableQuery<XdIdpGroup> by xdChildren0_N(XdIdpGroup::container)
        val users: XdMutableQuery<XdIdpUser> by xdChildren0_N(XdIdpUser::container)
        val groupMemberships: XdMutableQuery<XdIdpGroupMembership> by xdChildren0_N(XdIdpGroupMembership::container)
    }

    class XdIdpUser(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdIdpUser>()

        var container by xdLink1(XdBaseIdpData::users, onTargetDelete = OnDeletePolicy.CASCADE)
    }


    class XdAzureIdpData(entity: Entity) : XdBaseIdpData(entity) {
        companion object : XdNaturalEntityType<XdAzureIdpData>()
    }

    class XdOktaIdpData(entity: Entity) : XdBaseIdpData(entity) {
        companion object : XdNaturalEntityType<XdOktaIdpData>()
    }

    class XdJbaIdpData(entity: Entity) : XdBaseIdpData(entity) {
        companion object : XdNaturalEntityType<XdJbaIdpData>()
    }

    class XdIdpGroupMembership(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdIdpGroupMembership>()

        var container by xdLink1(XdBaseIdpData::groupMemberships, onTargetDelete = OnDeletePolicy.CASCADE)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(XdIdpGroup, XdIdpGroupMembership, XdJbaIdpData, XdOktaIdpData, XdAzureIdpData, XdIdpUser,XdBaseIdpData)
    }

    @Test
    fun `provided entities should be validatable`() {
        logger.info("If you see this log-line link validator done it's job good.")
    }


}

