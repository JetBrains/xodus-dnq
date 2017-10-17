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

Methods that create delegates for simple properties accept optional parameters `dbName` and `constraints`. By default Xodus-DNQ
uses Kotlin-property name to name the property in Xodus database. Parameter `dbName` helps to override this.

Parameter `constraints` is a closure that has `PropertyConstraintsBuilder` as a receiver. Using this parameter
you can set up property constraints that will be checked before transaction flush. Xodus-DNQ defines several
useful constraints for `String` and `Number` types, but it is simple to defined your own constraints.    

Methods to create delegates for required simple properties have parameter `unique: Boolean`. By default its value is `false`.
If its value is `true`, Xodus-DNQ will check on flush uniqueness of property value among instances of the persistent
class.

##### Optional integer property
If its value is not defined in database the property returns `0`.

See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
var age: xdIntProp { min(0) }  // Optional non-negative Int property with database name `age`.
var rank: xdIntProp(dbName = "grade") // Optional Int property with database name `grade`.
```

##### Required integer property
Xodus-DNQ checks on flush that property value is defined. 

See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
var age: xdRequiredIntProp { min(0) }  // Required non-negative Int property with database name `age`.
var rank: xdRequiredIntProp(dbName = "grade") // Required Int property with database name `grade`.
var id: xdRequiredIntProp(unique = true) // Unique required Int property with database name `id`.
```

##### Optional long property
If its value is not defined in database the property returns `0L`.

See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Optional non-negative Long property with database name `salary`.
var salary: xdLongProp() { min(0) }  
```

##### Required long property
Xodus-DNQ checks on flush that property value is defined. 

See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Unique required Long property with database name `id`.
var id: xdRequiredLongProp(unique = true) 
```

##### Nullable long property
See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Non-negative nullable Long property with database name `salary`.
var salary: xdNullableLongProp { min(0) }  
```

##### Optional boolean property
If its value is not defined in database the property returns `false`.

```kotlin
// Optional Boolean property with database name `isGuest`.
var isGuest: xdBooleanProp() 
```

##### Nullable boolean property

```kotlin
// Nullable Boolean property with database name `isFemale`.
var isFemale: xdNullableBooleanProp() 
```

##### Optional string property
Nullable String property. Optional parameter `trimmed: Boolean` enables string trimming on value set, i.e. when
you assign a value to such property all leading and trailing spaces are removed.

See also constraints: [regex()](#regex), [email()](#email), [containsNone()](#none-of-characters), 
[alpha()](#letters-only), [numeric()](#digits-only), [alphaNumeric()](#digits-and-letters-only), [url()](#url), 
[uri()](#uri), [length()](#string-length)

```kotlin
// Optional nullable String property with database name `lastName`.
var lastName: xdStringProp(trimmed=true)
```

##### Required string property
Not-null String property. Xodus-DNQ will check on flush that the property has some non-empty value. Note that Xodus 
treats empty string as `null`. So empty string does not pass require check.

Optional parameter `trimmed: Boolean` enables string trimming on value set, i.e. when
you assign a value to such property all leading and trailing spaces are removed.

See also constraints: [regex()](#regex), [email()](#email), [containsNone()](#none-of-characters), 
[alpha()](#letters-only), [numeric()](#digits-only), [alphaNumeric()](#digits-and-letters-only), [url()](#url), 
[uri()](#uri), [length()](#string-length)

```kotlin
// Required unique String property with database name `uuid`.
var uuid: xdRequiredStringProp(unique=true)
```

##### Optional Joda DateTime property
Nullable DateTime property. Xodus does not have built-in support for date-time simple properties. This property is
actually wrapping nullable Long property and storing unix epoch timestamp.

```kotlin
// Optional nullable DateTime property with database name `createdAt`.
var createdAt: xdDateTimeProp()
```

##### Required Joda DateTime property
Not-null DateTime property. Xodus-DNQ will check on flush that the property value is defined.  

Xodus does not have built-in support for date-time simple properties. This property is
actually wrapping not-null Long property and storing unix epoch timestamp.

```kotlin
// Required not-null DateTime property with database name `createdAt`.
var createdAt: xdRequiredDateTimeProp()
```

##### Optional blob property
Nullable property of type InputStream. Xodus stores massive blobs as separate files on disk. 
Xodus also does not build indices for blob properties, so you cannot filter or sort `XdQuery` by this property.

```kotlin
// Optional nullable InputStream property with database name `image`.
var image: xdBlobProp()
```

##### Required blob property
Not-null property of type InputStream. Xodus-DNQ will check on flush that the property value is defined. 
Xodus stores massive blobs as separate files on disk. 
Xodus also does not build indices for blob properties, so you cannot filter or sort `XdQuery` by this property.

```kotlin
// Required not-null InputStream property with database name `image`.
var image: xdRequiredBlobProp()
```

##### Optional string blob property
Nullable property of type String stored in Xodus database as blob. 
Xodus stores massive blobs as separate files on disk. 
Xodus also does not build indices for blob properties, so you cannot filter or sort `XdQuery` by this property.

```kotlin
// Optional nullable String property with database name `description`.
var description: xdBlobStringProp()
```

##### Required string blob property
Required not-null property of type String stored in Xodus database as blob. 
Xodus-DNQ will check on flush that the property value is defined.
Xodus stores massive blobs as separate files on disk. 
Xodus also does not build indices for blob properties, so you cannot filter or sort `XdQuery` by this property.

```kotlin
// Required not-null String property with database name `description`.
var description: xdRequiredBlobStringProp()
```

#### Property constraints

##### Regex
Checks that string property value matches regular expression.

```kotlin
var javaIdentifier by xdStringProp {
    regex(Regex("[A-Za-z][A-Za-z0-9_]*"), "is not a valid Java identifier")
}
```

##### Email
Checks that string property value is a valid email. Optionally accepts custom regular expression to verify email.
```kotlin
var email by xdStringProp { email() }
```

##### None of characters
Checks that string property value contains none of the specified characters. 

```kotlin
var noDash by xdStringProp { containsNone("-") }
```

##### Letters only
Checks that string property value contains only letter characters. 

```kotlin
var alpha by xdStringProp { alpha() }
```

##### Digits only
Checks that string property value contains only digit characters. 

```kotlin
var number by xdStringProp { numeric() }
```

##### Digits and letters only
Checks that string property value contains only digit and letter characters. 

```kotlin
var base64 by xdStringProp { alphaNumeric() }
```

##### URL
Checks that string property value is a valid URL. 

```kotlin
var url by xdStringProp { url() }
```

##### URI
Checks that string property value is a valid URL. 

```kotlin
var uri by xdStringProp { uri() }
```

##### String length
Checks that length of string property value falls into defined range. 

```kotlin
var badPassword by xdStringProp { length(min = 5, max = 10) }
```

##### URI
Checks that property value is defined if provided closure returns `true`. 

```kotlin
var main by xdStringProp()
var dependent by xdLongProp { requireIf { main != null } }
```

##### Min value 
Checks that number property value is more or equals than given value.

```kotlin
var timeout by xdIntProp { min(1000) }
```

##### Max values 
Checks that number property value is less or equals than given value.

```kotlin
var timeout by xdIntProp { max(10_000) }
```

#### Custom Property Constraints
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
