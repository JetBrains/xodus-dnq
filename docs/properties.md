Persistent class can have simple properties and links to other persistent classes implemented by property delegates.

```kotlin
class XdUser(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<XdUser>()

    var login by xdRequiredStringProp(unique = true, trimmed = true) { login() }
    var visibleName by xdRequiredStringProp(trimmed = true)
    var banned by xdBooleanProp()
    var created by xdRequiredDateTimeProp()
    var lastAccessTime by xdDateTimeProp()
    val groups by xdLink0_N(XdGroup::users)
    val sshPublicKeys by xdChildren0_N(XdSshPublicKey::user)

    override val presentation: String
        get() = login
}

class XdSshPublicKey(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<XdSshPublicKey>()
    
    var user: XdBaseXdUser by xdParent(XdUser::sshPublicKeys)
    var data by xdBlobStringProp()
    var fingerPrint by xdRequiredStringProp(unique = true)
}

class XdGroup(override val entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdGroup>()

    var name by xdRequiredStringProp(unique = true) { containsNone("<>/") }
    var parentGroup: XdGroup by xdParent(XdGroup::subGroups)
    val subGroups by xdChildren0_N(XdGroup::parentGroup)
    val users by xdLink0_N(XdUser::groups, onDelete = CLEAR, onTargetDelete = CLEAR)

    override val presentation: String
        get() = name
}
```

### Simple properties

TO BE DONE

### Links
There are three types of links: directed links, bi-directed links and aggregation links. 

Most of the methods that create delegates for links accept optional parameter `dbPropertyName`. By default Xodus-DNQ
uses Kotlin-property name to reference the link in Xodus database. Parameter `dbPropertyName` helps to override this.  

#### On Delete Policy
Most of the methods that create delegates for links accept optional parameters `onDelete` and `onTargetDelete`.
This parameters defines what Xodus-DNQ should do with the link on this entity delete or on the link target delete.
Available options are
* `OnDeletePolicy.FAIL` -- Fail transaction if entity is deleted but link still points to it.
* `OnDeletePolicy.CLEAR` -- Clear link to deleted entity.
* `OnDeletePolicy.CASCADE` -- If entity is delete and link still exists, then delete entity on the opposite 
link end as well.
* `OnDeletePolicy.FAIL_PER_TYPE` -- Fail transaction with a custom message if entity is deleted but link still 
points to it. One message per entity type.
* `OnDeletePolicy.FAIL_PER_ENTITY` -- Fail transaction with a custom message if entity is deleted but link still 
points to it.  One message per entity.

##### Directed [0..1] association
Optional unidirectional association. 
Type of Kotlin-property defined by this delegate is nullable. 
First parameter is companion object of persistent class that is an opposite end of the association.
```kotlin 
var directedOptionalLink by xdLink0_1(XdTarget, onTargetDelete = OnDeletePolicy.CLEAR)
```

##### Directed [1] association
Required unidirectional association. 
Type of Kotlin-property defined by this delegate is not-null. 
Xodus-DNQ checks on flush that the link points to some entity.
First parameter is companion object of persistent class that is an opposite end of the association.
```kotlin 
var directedRequiredLink by xdLink1(XdTarget)
```

##### Directed [0..N] association
Multi-value unidirectional association. 
Type of Kotlin-property defined by this delegate is `XdMutableQuery`.
First parameter is companion object of persistent class that is an opposite end of the association.
```kotlin 
var users by xdLink0_N(XdUser)
```

##### Directed [1..N] association
Multi-value unidirectional association. 
Type of Kotlin-property defined by this delegate is `XdMutableQuery`. 
Xodus-DNQ checks on flush that the link contains at least one entity.
First parameter is companion object of persistent class that is an opposite end of the association.
```kotlin 
var users by xdLink1_N(XdUser)
```

#### Bidirectional associations

For bidirectional associations Xodus-DNQ maintains both ends of the links. For example, if there is a bidirectional
link between `XdUser::groups` and `XdGroup::users`, and you add some group to `user.groups.add(group)` 
Xodus-DNQ will automatically add `user` to `group.users`.

##### Undirected [0..1] association
Optional bidirectional association. 
Type of Kotlin-property defined by this delegate is nullable. 
First parameter is a property reference to an opposite end of the association.
```kotlin
val group by xdLink0_1(XdGroup::users)
```

##### Undirected [1] association
Required bidirectional association. 
Xodus-DNQ checks on flush that the link points to some entity.
Type of Kotlin-property defined by this delegate is not-null. 
First parameter is a property reference to an opposite end of the association.
```kotlin
val group by xdLink1(XdGroup::users)
```

##### Undirected [0..N] association
Multi-value bidirectional association. 
Type of Kotlin-property defined by this delegate is `XdMutableQuery`.
First parameter is a property reference to an opposite end of the association.
```kotlin
val groups by xdLink0_N(XdGroup::users)
```

##### Undirected [1..N] association
Multi-value bidirectional association. 
Type of Kotlin-property defined by this delegate is `XdMutableQuery`.
Xodus-DNQ checks on flush that the link contains at least one entity.
First parameter is a property reference to an opposite end of the association.
```kotlin
val groups by xdLink1_N(XdGroup::users)
```

#### Aggregations
Aggregations or parent-child association are auxiliary type of links with some predefined behavior.
1. If persistent class of an entity has at least one parent link defined, it is considered as a child entity and 
it should have exactly one parent on flush.
2. On parent delete all its children are deleted as well.

##### Parent end [0..1] of aggregation association
Parent end of optional aggregation.
Type of Kotlin-property defined by this delegate is nullable. 
First parameter is a property reference to a child end of the association.
```kotlin
val profile by xdChild0_1(XdUser::profile)
```

##### Parent end [1] of aggregation association
Parent end of required aggregation.
Type of Kotlin-property defined by this delegate is not-null.
Xodus-DNQ checks on flush that the link points to some entity. 
First parameter is a property reference to a child end of the association.
```kotlin
val profile by xdChild1(XdUser::profile)
```

##### Parent end [0..N] of aggregation association
Parent end of multi-value aggregation. 
Type of Kotlin-property defined by this delegate is `XdMutableQuery`.
First parameter is a property reference to a child end of the association.
```kotlin
val subGroups by xdChildren0_N(XdGroup::parentGroup)
```

##### Parent end [1..N] of aggregation association
Parent end of multi-value aggregation. 
Type of Kotlin-property defined by this delegate is `XdMutableQuery`.
Xodus-DNQ checks on flush that the link contains at least one entity.
First parameter is a property reference to a child end of the association.
```kotlin
val contacts by xdChildren1_N(XdContact::user)
```

##### Child end of aggregation association, when only one parent link is defined for persistent class
Child end of aggregation. 
Type of Kotlin-property defined by this delegate is not-null.
Xodus-DNQ checks on flush that the link contains at least one entity.
First parameter is a property reference to a parent end of the association.
```kotlin
val user by xdParent(XdUser::contacts)
```

##### Child end of aggregation association, when multiple parent links are defined for persistent class
Child end of aggregation. 
Type of Kotlin-property defined by this delegate is nullable. 
First parameter is a property reference to a parent end of the association.
```kotlin
val parentGroup by xdMultiParent(XdGroup::subGroups)
val parentOfRootGroup by xdMultiParent(XdRoot::rootGroup)
```

#### Links to extension properties 

Bidirectional link can also point to Kotlin extension property on one side.

```kotlin
class XdUser(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<XdUser>()
    var login by xdRequiredStringProp(unique = true)
}

class SecretInfo(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<Secret>()
    var info by xdBlobStringProp()
    var user: XdUser by xdLink1(XdUser::secret)
}

var XdUser.secret by xdLink1(SecretInfo::user)
```

### isDefined and getSafe

Required properties and links of cardinality `1` have non-null types in Kotlin. But for new entities
that were not committed yet, the properties can be not defined yet. To check if a property has a value one can use
method `isDefined`. To get a value of such a property safely there is a method `getSafe`.
```kotlin
class XdUser(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<XdUser>()
    var login by xdRequiredStringProp(unique = true)
}

fun `isDefined returns false for undefined properties`() {
    store.transactional {
        val user = XdUser.new()
        assertEquals(false, user.isDefined(XdUser::login))
    }
}

fun `isDefined returns true for defined properties`() {
    store.transactional {
        val user = XdUser.new { login = "zeckson" }
        assertEquals(true, user.isDefined(XdUser::login))
    }
}

fun `getSafe returns null for undefined properties`() {
    store.transactional {
        val user = XdUser.new()
        assertEquals(null, user.getSafe(XdUser::login))
    }
}

fun `getSafe returns property value for defined properties`() {
    store.transactional {
        val user = XdUser.new { login = "zeckson" }
        assertEquals("zeckson", user.getSafe(XdUser::login))
    }
}
```

### Unique Indices

Xodus-DNQ can check uniqueness constraints for composite keys. To enable it composite key index should be defined.
```kotlin
class XdAPI(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<XdAPI>() {
        override val compositeIndices = listOf(
                listOf(XdAPI::service, XdAPI::key)
        )
    }

    var key by xdRequiredStringProp()
    var service by xdLink1(XdService)    
}
```

It is also possible to define an index with a single property to make it unique. Technically two following code blocks
do the same thing.

```kotlin
class XdAPI(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<XdAPI>()

    var key by xdRequiredStringProp(unique=true)
    var name by xdRequiredStringProp(unique=true)
}
```

```kotlin
class XdAPI(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<XdAPI>() {
        override val compositeIndices = listOf(
                listOf(XdAPI::key),
                listOf(XdAPI::name)
        )
    }

    var key by xdRequiredStringProp()
    var name by xdRequiredStringProp()
}
```

Explicit indices for a single property can be useful when you want to make some property of a parent class to be 
unique for instances of a child class.
