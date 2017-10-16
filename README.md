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

## Quick Start Guide

### Declare Persistent Classes

To define persistent model of your application you need to define
persistent classes.

Persistent class should: 
1. Have constructor with a single argument of type `jetbrains.exodus.entitystore.Entity`
1. Inherit from class `XdEntity`.
1. Have companion object of type `XdEntityType` parameterized with ths class itself.

```kotlin
class XdUser(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<XdUser>()

    var login by xdRequiredStringProp(unique = true, trimmed = true)
    var gender by xdLink0_1(XdGender)
    val contacts by xdChildren0_N(XdContact::owner)

    override fun toString(): String {
        return "$login, ${gender?.presentation ?: "N/A"}, ${contacts.asSequence().joinToString()}"
    }
}
```

To define persistent class properties use xd-methods that create
property delegates. 
```kotlin 
var login by xdRequiredStringProp(unique = true, trimmed = true)
```

`xdRequiredStringProp` defines not-null string property. Xodus will check that 
the property is initialized with non-empty value on transaction flush.

The property is marked to be `unique=true`, i.e. there can be no two instances 
of `XdUser` with the same `login`. Xodus will check it on transaction flush as well.
   
Flag `trimmed=true` means that on property set leading and trailing spaces will be
removed.

`TODO: link to list of xd-methods to define properties` 

```kotlin
var gender by xdLink0_1(XdGender)
```
`xdLink0_1` defines a directed link between persistent class `XdUser` and
persistent class `XdGender`.  

```kotlin
val contacts by xdChildren0_N(XdContact::owner)
```
`xdChildren0_N` defines a bi-directed aggregation link between persistent 
class `XdUser` and persistent class `XdContact`. `0_N` means that
the link can have multiple values. Parameter `XdContact::owner` points
to the property of `XdContact` that represents the opposite end of the link.

`TODO: link to list of xd-methods to define links`

You can also define methods and properties that are not persistent. Like
`toString` method of `XdUser`.

```kotlin
class XdGender(entity: Entity) : XdEnumEntity(entity) {
    companion object : XdEnumEntityType<XdGender>() {
        val FEMALE by enumField { presentation = "F" }
        val MALE by enumField { presentation = "M" }
        val OTHER by enumField { presentation = "-" }
    }

    var presentation by xdRequiredStringProp()
        private set
}
```
Enumeration persistent class simplifies a task of creating dictionaries 
stored in database. Enumeration persistent class should extend `XdEnumEntity`. 
Its companion object should be inherited from `XdEnumEntityType`. Its values 
are defined as properties of the companion implemented by property delegate
`enumField`.

```kotlin
val FEMALE by enumField { presentation = "F" }
```
Enumeration values are created or updated on application meta-data initialization.
So all values are available while application runs.

Method `enumField` can optionally have closure parameter that is used to initialize
the enum value on meta-data initialization.

```kotlin
abstract class XdContact : XdEntity() {
    companion object : XdNaturalEntityType<XdContact>()

    var owner: XdUser by xdParent(XdUser::contacts)
    var isVerified by xdBooleanProp()

    abstract fun verify()
}

class XdEmail(override val entity: Entity) : XdContact() {
    companion object : XdNaturalEntityType<XdEmail>()

    var address by xdRequiredStringProp { email() }

    override fun verify() {
        isVerified = true
    }

    override fun toString(): String {
        return "$address ${if (isVerified) "✅" else "❎"}"
    }
}
```

Persistent classes can be defined abstract or open and can extend each other. 
Like `XdEmail` extends `XdContact`.

```kotlin
var address by xdRequiredStringProp { email() }
```
String property `address` has `email` constraint defined. This constraint 
is check on transaction flush as well.

### Initialize Xodus Database
```kotlin
fun initXodus(): TransientEntityStore {
    XdModel.registerNodes(
            XdGender,
            XdUser,
            XdContact,
            XdEmail
    )

    val databaseHome = File(System.getProperty("user.home"), "xodus-dnq-sample-app")

    val store = StaticStoreContainer.init(
            dbFolder = databaseHome,
            environmentName = "db"
    )

    initMetaData(XdModel.hierarchy, store)

    return store
}
```

```kotlin
XdModel.registerNodes()
```
To make Xodus DNQ to know about your persistent meta-model, you have to 
register persistent classes using `XdModel.registerNodes` method. `XdModel`
also has helper methods to find all persistent classes in classpath.

```kotlin
StaticStoreContainer.init()
```
To use Xodus DNQ you need to initialize `TransientEntityStore`. There is
a helper method `StaticStoreContainer.init` that creates a database in a folder
and stores its value in a static field, but you can also create 
the `TransientEntityStore` yourself, for example if you want to use several
Xodus databases in your application. 

```kotlin
initMetaData(XdModel.hierarchy, store)
```
Method `initMetaData` initialize persistent meta-data and sets up all the 
constraints. 

### Query Data
```kotlin
fun main(args: Array<String>) {
    val store = initXodus()

    val user = store.transactional {
        val zecksonLogin = "zeckson"

        val zeckson = XdUser.all().query(XdUser::login eq zecksonLogin).firstOrNull()

        zeckson ?: XdUser.new {
            login = zecksonLogin
            gender = XdGender.MALE
            contacts.add(XdEmail.new {
                address = "zeckson@gmail.com"
            })
        }
    }

    store.transactional {
        for (contact in user.contacts) {
            contact.verify()
        }
    }
}
```

```kotlin
store.transactional { ... }
```
All operations with a Xodus database should happen in a transaction.

```kotlin
XdUser.all().query(XdUser::login eq zecksonLogin)
```
To query Xodus database effectively you may use `XdQuery`. For example multi-value links (e.g. `XdUser::contacts`) have 
type `XdQuery`. There is a set of operations to filter, map, and sort `XdQueries`. The call above takes all database 
instances of `XdUser` and filters those which have `zecksonLogin` as a value of link `XdUser::login`.

```kotlin
for (contact in user.contacts) {
    contact.verify()
}
```
There are methods convert `XdQuery` to `Sequence` and `List`. Operator `iterator` is defined for `XdQuery` as 
an extension function, this makes it possible to user `XdQuery` in for-loops.

```kotlin
XdUser.new {
    login = zecksonLogin
    gender = XdGender.MALE
    contacts.add(XdEmail.new {
        address = "zeckson@gmail.com"
    })
}
```
To create new instances of a persistent class you may use method `XdEntityType.new` that actually creates new entity
and passes it to a parameter closure as a reciever. This enables you to initialize new entities in a nice 
kotlin-builder manner.  

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
