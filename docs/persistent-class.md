### Persistent Class

Each persistent class should:
1. Inherit from class `XdEntity`.
2. Have constructor with a single argument of type `jetbrains.exodus.entitystore.Entity`
3. Have companion object of type `XdEntityType`

```kotlin
class XdUser(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<XdUser>()
}
```

### Inheritance

Xodus-DNQ support inheritance. Inherited persistent class still should have a single argument constructor accepting 
`Entity` and a companion object of type `XdEntityType`. But it can have other persistent class as a super-type. 

```kotlin
abstract class BaseEntity(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<BaseEntity>()
    
    val propertyOfBaseClass by xdStringProp()
}

class SubEntity(entity: Entity) : BaseEntity(entity) {
    companion object : XdNaturalEntityType<SubEntity>()
}
```

### Enumerations

For immutable dictionaries it is handy to use persistent enumerations. Elements of persistent enumeration
are automatically created and updated on entity meta-model initialization.

```kotlin
class State(entity: Entity) : XdEnumEntity(entity) {
    companion object : XdEnumEntityType<MyEnum>() {
        val OPEN by enumField { title = "open" }
        val IN_PROGRESS by enumField { title = "in progress" }
        val CLOSED by enumField { title = "closes" }
    }

    var title by xdRequiredStringProp(unique = true)
}

class Issue(override val entity: Entity): XdEntity() {
    companion object : XdNaturalEntityType<Issue>()

    val state by xdLink(State)
}
``` 

### Singletons

There could be singleton entities. It means that exactly one instance of such entity should exist
in a database. For example you could store application settings in such entity.  

```kotlin
class TheKing(override val entity: Entity) : XdEntity() {
    companion object : XdSingletonEntityType<TheKing>() {
        override fun TheKing.initSingleton() {
            name = "Elvis"
        }
    }

    var name by xdRequiredStringProp()
}
    
fun getKing(store: TransientEntityStore): TheKing {
    return store.transactional {
        TheKing.get()
    }
}    
``` 