package kotlinx.dnq

import com.sun.javaws.exceptions.InvalidArgumentException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.util.getDBName
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.assertThat
import org.junit.Before
import org.junit.Test


class DBNameTest {

    open class Parent(override val entity: Entity) : XdEnumEntity(entity) {
        companion object : XdNaturalEntityType<Parent>()

        open val overriddenPropWithDbName by xdStringProp(dbName = "overriddenProp")
        open val overriddenPropWithoutDbName by xdStringProp(dbName = "overriddenInChild")
        val parentPropWithDbName by xdStringProp(dbName = "parentProp")
    }

    class Child(entity: Entity) : Parent(entity) {
        companion object : XdNaturalEntityType<Child>()

        override val overriddenPropWithDbName by xdStringProp(dbName = "overriddenChildProp")
        override val overriddenPropWithoutDbName by xdStringProp()

        val propWithDbName by xdStringProp(dbName = "dbProperty")
        val propWithoutDbName by xdStringProp()
    }

    class NoXdEntityTypeEntity(override val entity: Entity) : XdEntity() {
        companion object

        val prop by xdStringProp()
    }

    @Before
    fun before() {
        XdModel.registerNode(Child)
    }

    @Test
    fun `getDBName should return dbName if it exists`() {
        assertThat(Child::propWithDbName.getDBName(), equalTo("dbProperty"))
        assertThat(Child::propWithoutDbName.getDBName(), equalTo(Child::propWithoutDbName.name))
    }

    @Test
    fun `getDBName should return dbName for parent's property`() {
        assertThat(Child::parentPropWithDbName.getDBName(), equalTo("parentProp"))
        assertThat(Child::name.getDBName(), equalTo(XdEnumEntity.ENUM_CONST_NAME_FIELD))
    }

    @Test
    fun `getDBName should take more priority to children properties`() {
        assertThat(Child::overriddenPropWithDbName.getDBName(), equalTo("overriddenChildProp"))
        assertThat(Child::overriddenPropWithoutDbName.getDBName(), equalTo(Child::overriddenPropWithoutDbName.name))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getDBName should throw on properties of an XdEntity class without XdEntityType companion object`() {
        NoXdEntityTypeEntity::prop.getDBName()
    }
}