[![JetBrains incubator project](http://jb.gg/badges/incubator.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

# xodus-dnq
Kotlin library for the data definition and query over Xodus

## Install to your project
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.jetbrains.xodus/dnq/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.jetbrains.xodus/dnq)

List of released versions is available at https://github.com/JetBrains/xodus-dnq/releases.

#### Gradle
```groovy
repositories {
    mavenCentral()
}
compile 'org.jetbrains.xodus:dnq:${version}'
```
#### Maven
```xml
<dependency>
    <groupId>org.jetbrains.xodus</groupId>
    <artifactId>dnq</artifactId>
    <version>$version</version>
</dependency>
```


## Data Definition

### Persistent Class

Each persistent class should 

1. Inherit from class `XdEntity`.
2. Have constructor with a single argument of type `jetbrains.exodus.entitystore.Entity`
3. Have companion object of type `XdEntityType`

```kotlin
class User(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<User>()
}
```

### Links and Properties

Persistent class can have simple properties and links to other persistent classes implemented by property delegates.
There are three types of links: directed links, bi-directed links and aggregation links.
```kotlin
class User(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<User>()

    var login by xdRequiredStringProp(unique = true, trimmed = true) { login() }
    var visibleName by xdRequiredStringProp(trimmed = true)
    var banned by xdBooleanProp()
    var created by xdRequiredDateTimeProp()
    var lastAccessTime by xdDateTimeProp()
    val groups by xdLink0_N(Group::users)
    val sshPublicKeys by xdChildren0_N(SshPublicKey::user)

    override val presentation: String
        get() = login
}

class SshPublicKey(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<SshPublicKey>()
    
    var user: XdBaseUser by xdParent(User::sshPublicKeys)
    var data by xdBlobStringProp()
    var fingerPrint by xdRequiredStringProp(unique = true)
}

class Group(override val entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Group>()

    var name by xdRequiredStringProp(unique = true) { containsNone("<>/") }
    var parentGroup: Group by xdParent(Group::subGroups)
    val subGroups by xdChildren0_N(Group::parentGroup)
    val users by xdLink0_N(User::groups, onDelete = CLEAR, onTargetDelete = CLEAR)

    override val presentation: String
        get() = name
}
```
#### Links to extension properties 

Bi-directional link can points to kotlin extension property on one side.
```kotlin
class User(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<User>()
    var login by xdRequiredStringProp(unique = true)
}

class SecretInfo(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<Secret>()
    var info by xdBlobStringProp()
    var user: User by xdLink1(User::secret)
}

var User.secret by xdLink1(SecretInfo::user)
```

#### isDefined and getSafe

Required properties and links of cardinality `1` have non-null types in Kotlin. But for new entities
that were not committed yet, the properties can be not defined yet. To check if a property has a value one can use
method `isDefined`. To get a value of such a property safely there is a method `getSafe`.
```kotlin
class User(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<User>()
    var login by xdRequiredStringProp(unique = true)
}

fun `isDefined returns false for undefined properties`() {
    store.transactional {
        val user = User.new()
        assertEquals(false, user.isDefined(User::login))
    }
}

fun `isDefined returns true for defined properties`() {
    store.transactional {
        val user = User.new { login = "zeckson" }
        assertEquals(true, user.isDefined(User::login))
    }
}

fun `getSafe returns null for undefined properties`() {
    store.transactional {
        val user = User.new()
        assertEquals(null, user.getSafe(User::login))
    }
}

fun `getSafe returns property value for defined properties`() {
    store.transactional {
        val user = User.new { login = "zeckson" }
        assertEquals("zeckson", user.getSafe(User::login))
    }
}
```

### Inheritance

```kotlin
abstract class BaseEntity(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<BaseEntity>()
    
    val propertyOfBaseClass by xdStringProp()
}

class SubEntity(entity: Entity) : BaseEntity(entity) {
    companion object : XdNaturalEntityType<SubEntity>()
}
```

## Data Query 

### Build Meta-Model

All persistent classes should be registered in `XdModel`, there are several ways to do it.
```kotlin
// 1. Register persistent class explicitly
XdModel.registerNode(User)

// 2. Scan Java-Classpath
XdModel.scanJavaClasspath()

// 3. Scan specific URLs
if (classLoader is URLClassLoader) {
    XdModel.scanURLs("URLClassLoader.urls", classLoader.urLs)
}
```

## Transactions
All DB-operations should happen in transactions. XdEntities from one transaction can be safely used
in another one, i.e. one can store a reference to an entity outside a transaction.

## New

```kotlin
class User(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<User>()

    var login by xdRequiredStringProp(unique = true)
}

fun createUser(store: TransientEntityStore, login: String): User {
    return store.transactional {
        User.new {
            this.login = login 
        }
    }
}
```

## Query

## Legacy with meta-model defined in MPS

## Enumerations

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

## Singletons

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

## Indices

## findOrCreate
