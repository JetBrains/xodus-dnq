# xodus-dnq
Kotlin library for the data definition and query over Xodus

## Dependecies
[TeamCity project](https://buildserver.labs.intellij.net/project.html?projectId=Ring_Tools_XodusDnq) to build and


```groovy
repositories {
    maven { url 'http://repo.labs.intellij.net/webr-dnq' }
}

compile 'kotlinx.dnq:dnq:${ext.version}'
```
where `ext.version` is a version from https://github.com/JetBrains/xodus-dnq/releases

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

// 3. Scan web classpath 
XdModel.scanWebClasspath(servletContext)

// 4. Scan specific URLs
if (classLoader is URLClassLoader) {
    XdModel.scanURLs("URLClassLoader.urls", classLoader.urLs)
}

```

### Transactions

## New 

## Query

## Legacy with meta-model defined in MPS

## Enumerations

## Indices

## findOrCreate