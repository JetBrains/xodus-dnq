package kotlinx.dnq.query

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import org.junit.Test

class FilterIsInstanceTest : DBTest() {

    open class Parent(entity: Entity): XdEntity(entity) {
        companion object : XdNaturalEntityType<Parent>()
    }

    class Child(entity: Entity): Parent(entity) {
        companion object : XdNaturalEntityType<Child>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Parent, Child)
    }

    @Test
    fun `filter children`() {
        transactional {
            Parent.new()
            Child.new()
            Child.new()
        }

        transactional {
            assertThat(Parent.all().filterIsInstance(Child)).hasSize(2)
        }
    }

    @Test
    fun `filter not children`() {
        transactional {
            Parent.new()
            Child.new()
            Child.new()
        }

        transactional {
            assertThat(Parent.all().filterIsNotInstance(Child)).hasSize(1)
        }
    }

    @Test
    fun `filter parent`() {
        transactional {
            Parent.new()
            Child.new()
            Child.new()
        }

        transactional {
            assertThat(Parent.all().filterIsInstance(Parent)).hasSize(3)
        }
    }

    @Test
    fun `filter not parent`() {
        transactional {
            Parent.new()
            Child.new()
            Child.new()
        }

        transactional {
            assertThat(Parent.all().filterIsNotInstance(Parent)).isEmpty()
        }
    }
}