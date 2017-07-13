package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.XdMutableQuery
import org.junit.Assert
import org.junit.Test


class AbstractRelationTest {
    abstract class XdBaseGroup(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<XdBaseGroup>()

        abstract val parent: XdBaseGroup?
        val subgroups: XdMutableQuery<XdBaseGroup> by xdChildren0_N(XdBaseGroup::parent)
    }

    class XdGroup(entity: Entity) : XdBaseGroup(entity) {
        companion object : XdNaturalEntityType<XdGroup>()

        override var parent by xdParent(XdBaseGroup::subgroups)
    }

    class XdAnyoneGroup(entity: Entity) : XdBaseGroup(entity) {
        companion object : XdNaturalEntityType<XdAnyoneGroup>()

        override val parent: XdBaseGroup? = null
    }

    @Test
    fun `should throw on using links with abstract opposite fields`() {
        try {
            XdModel.registerNodes(XdGroup, XdAnyoneGroup)
            Assert.fail()
        } catch (ex: UnsupportedOperationException) {
            assertThat(ex.message).isEqualTo("Property XdBaseGroup#subgroups has abstract opposite field XdBaseGroup::parent")
        }
    }
}