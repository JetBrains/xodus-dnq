---
layout: page
title: Persistent Properties
---

Persistent class can have simple properties and links to other persistent classes implemented by property delegates.

```kotlin
class XdUser(entity: Entity) : XdEntity(entity) {
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

class XdSshPublicKey(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdSshPublicKey>()
    
    var user: XdBaseXdUser by xdParent(XdUser::sshPublicKeys)
    var data by xdBlobStringProp()
    var fingerPrint by xdRequiredStringProp(unique = true)
}

class XdGroup(entity: Entity) : XdEntity(entity) {
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

#### Byte

##### xdByteProp --- optional byte property
- Property type: `Byte`.
- If its value is not defined in database the property returns `0`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Optional non-negative Byte property with database name `age`.
var age by xdByteProp { min(0) }
```

##### xdRequiredByteProp --- required byte property
- Property type: `Byte`.
- If its value is not defined in database the property returns `0`.
- Xodus-DNQ checks on flush that property value is defined. 
- If parameter `unique` is `true` then Xodus-DNQ will check on flush uniqueness 
of the property value among instances of the persistent class.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Unique required Byte property with database name `id`. 
var id by xdRequiredByteProp(unique = true) 
```

##### xdNullableByteProp --- nullable byte property
- Property type: `Byte?`.
- If its value is not defined in database the property returns `null`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Non-negative nullable Byte property with database name `salary`.
var salary by xdNullableByteProp { min(0) }  
```
 
#### Short

##### xdShortProp --- optional short property
- Property type: `Short`.
- If its value is not defined in database the property returns `0`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Optional non-negative Short property with database name `age`.
var age by xdShortProp { min(0) }
```

##### xdRequiredShortProp --- required short property
- Property type: `Short`.
- If its value is not defined in database the property returns `0`.
- Xodus-DNQ checks on flush that property value is defined. 
- If parameter `unique` is `true` then Xodus-DNQ will check on flush uniqueness 
of the property value among instances of the persistent class.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Unique required Short property with database name `id`. 
var id by xdRequiredShortProp(unique = true) 
```

##### xdNullableShortProp --- nullable short property
- Property type: `Short?`.
- If its value is not defined in database the property returns `null`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Non-negative nullable Short property with database name `salary`.
var salary by xdNullableShortProp { min(0) }  
```

#### Int

##### xdIntProp --- optional integer property
- Property type: `Int`.
- If its value is not defined in database the property returns `0`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Optional non-negative Int property with database name `age`.
var age by xdIntProp { min(0) }

// Optional Int property with database name `grade`.
var rank by xdIntProp(dbName = "grade")
```

##### xdRequiredIntProp --- required integer property
- Property type: `Int`.
- If its value is not defined in database the property returns `0`.
- Xodus-DNQ checks on flush that property value is defined. 
- If parameter `unique` is `true` then Xodus-DNQ will check on flush uniqueness 
of the property value among instances of the persistent class.
- See also constraints: [min()](#min-value), [max()](#max-values).


```kotlin
// Required non-negative Int property with database name `age`.
var age by xdRequiredIntProp { min(0) }  

// Required Int property with database name `grade`.
var rank by xdRequiredIntProp(dbName = "grade")

// Unique required Int property with database name `id`. 
var id by xdRequiredIntProp(unique = true) 
```

##### xdNullableIntProp --- nullable integer property
- Property type: `Int?`.
- If its value is not defined in database the property returns `null`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Non-negative nullable Int property with database name `salary`.
var salary by xdNullableIntProp { min(0) }  
```

#### Long

##### xdLongProp --- optional long property
- Property type: `Long`.
- If its value is not defined in database the property returns `0L`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Optional non-negative Long property with database name `salary`.
var salary by xdLongProp() { min(0) }  
```

##### xdRequiredLongProp --- required long property
- Property type: `Long`.
- If its value is not defined in database the property returns `0L`.
- Xodus-DNQ checks on flush that property value is defined. 
- If parameter `unique` is `true` then Xodus-DNQ will check on flush uniqueness 
of the property value among instances of the persistent class.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Unique required Long property with database name `id`.
var id by xdRequiredLongProp(unique = true) 
```

##### xdNullableLongProp --- nullable long property
- Property type: `Long?`.
- If its value is not defined in database the property returns `null`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Non-negative nullable Long property with database name `salary`.
var salary by xdNullableLongProp { min(0) }  
```

#### Float

##### xdFloatProp --- optional float property
- Property type: `Float`.
- If its value is not defined in database the property returns `0F`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Optional non-negative Float property with database name `salary`.
var salary by xdFloatProp() { min(0) }  
```

##### xdRequiredFloatProp --- required float property
- Property type: `Float`.
- If its value is not defined in database the property returns `0F`.
- Xodus-DNQ checks on flush that property value is defined. 
- If parameter `unique` is `true` then Xodus-DNQ will check on flush uniqueness 
of the property value among instances of the persistent class.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Unique required Float property with database name `seed`.
var seed by xdRequiredFloatProp(unique = true) 
```

##### xdNullableFloatProp --- nullable float property
- Property type: `Float?`.
- If its value is not defined in database the property returns `null`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Non-negative nullable Float property with database name `salary`.
var salary by xdNullableFloatProp { min(0) }  
```

#### Double

##### xdDoubleProp --- optional double property
- Property type: `Double`.
- If its value is not defined in database the property returns `0.0`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Optional non-negative Double property with database name `salary`.
var salary by xdDoubleProp() { min(0) }  
```

##### xdRequiredDoubleProp --- required double property
- Property type: `Double`.
- If its value is not defined in database the property returns `0.0`.
- Xodus-DNQ checks on flush that property value is defined. 
- If parameter `unique` is `true` then Xodus-DNQ will check on flush uniqueness 
of the property value among instances of the persistent class.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Unique required Double property with database name `seed`.
var seed by xdRequiredDoubleProp(unique = true) 
```

##### xdNullableDoubleProp --- nullable double property
- Property type: `Double?`.
- If its value is not defined in database the property returns `null`.
- See also constraints: [min()](#min-value), [max()](#max-values).

```kotlin
// Non-negative nullable Double property with database name `salary`.
var salary by xdNullableDoubleProp { min(0) }  
```

#### Boolean
##### xdBooleanProp --- boolean property
- Property type: `Boolean`.
- If its value is not defined in database the property returns `false`.

```kotlin
// Optional Boolean property with database name `anonymous`.
var isGuest by xdBooleanProp(dbName = "anonymous") 
```

##### xdNullableBooleanProp --- nullable boolean property
- Property type: `Boolean?`.
- If its value is not defined in database the property returns `null`.

```kotlin
// Nullable Boolean property with database name `isFemale`.
var isFemale by xdNullableBooleanProp() 
```

#### String
##### xdStringProp --- optional string property
- Property type: `String?`.
- If its value is not defined in database the property returns `null`.
- Optional parameter `trimmed: Boolean` enables string trimming on value set, i.e. when
you assign a value to such property all leading and trailing spaces are removed.
- See also constraints: [regex()](#regex), [email()](#email), [containsNone()](#none-of-characters), 
[alpha()](#letters-only), [numeric()](#digits-only), [alphaNumeric()](#digits-and-letters-only), [url()](#url), 
[uri()](#uri), [length()](#string-length).

```kotlin
// Optional nullable String property with database name `lastName`.
var lastName by xdStringProp(trimmed = true)
```

##### xdRequiredStringProp --- required string property
- Property type: `String`.
- If its value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
- Xodus-DNQ checks on flush that property value is defined. Note that Xodus 
treats empty string as `null`. So empty string does not pass require check.
- Optional parameter `trimmed: Boolean` enables string trimming on value set, i.e. when
you assign a value to such property all leading and trailing spaces are removed.
- If parameter `unique` is `true` then Xodus-DNQ will check on flush uniqueness 
of the property value among instances of the persistent class.
- See also constraints: [regex()](#regex), [email()](#email), [containsNone()](#none-of-characters), 
[alpha()](#letters-only), [numeric()](#digits-only), [alphaNumeric()](#digits-and-letters-only), [url()](#url), 
[uri()](#uri), [length()](#string-length).

```kotlin
// Required unique String property with database name `uuid`.
var uuid by xdRequiredStringProp(unique=true)
```

#### Joda DateTime
##### xdDateTimeProp --- optional Joda DateTime property
- Property type: `org.joda.time.DateTime?`.
- If its value is not defined in database the property returns `null`.
- Xodus does not have built-in support for date-time simple properties. This property is
actually wrapping nullable Long property and is storing unix epoch timestamp.
- See also constraints: [isAfter()](#is-after), [isBefore()](#is-before), [past()](#past), [future()](#future).

```kotlin
// Optional nullable DateTime property with database name `createdAt`.
var createdAt by xdDateTimeProp()
```

##### xdRequiredDateTimeProp --- required Joda DateTime property
- Property type: `org.joda.time.DateTime`.
- If its value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
- Xodus-DNQ checks on flush that property value is defined.
- If parameter `unique` is `true` then Xodus-DNQ will check on flush uniqueness 
of the property value among instances of the persistent class.
- Xodus does not have built-in support for date-time simple properties. This property is
actually wrapping nullable Long property and is storing unix epoch timestamp.
- See also constraints: [isAfter()](#is-after), [isBefore()](#is-before), [past()](#past), [future()](#future).

```kotlin
// Required not-null DateTime property with database name `createdAt`.
var createdAt by xdRequiredDateTimeProp()
```

#### Blob
##### xdBlobProp --- optional blob property
- Property type: `InputStream?`.
- If its value is not defined in database the property returns `null`.
- Xodus stores massive blobs as separate files on disk.
- Xodus also does not build indices for blob properties. So indices for blob properties do not consume memory 
but you cannot filter or sort `XdQuery` by this property.

```kotlin
// Optional nullable InputStream property with database name `image`.
var image by xdBlobProp()
```

##### xdRequiredBlobProp --- required blob property
- Property type: `InputStream`.
- If its value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
- Xodus-DNQ checks on flush that property value is defined.
- Xodus stores massive blobs as separate files on disk.
- Xodus also does not build indices for blob properties. So indices for blob properties do not consume memory 
but you cannot filter or sort `XdQuery` by this property.

```kotlin
// Required not-null InputStream property with database name `image`.
var image by xdRequiredBlobProp()
```

#### String Blob
##### xdBlobStringProp --- optional string blob property
- Property type: `String?`.
- If its value is not defined in database the property returns `null`.
- Xodus stores massive blobs as separate files on disk.
- Xodus also does not build indices for blob properties. So indices for blob properties do not consume memory 
but you cannot filter or sort `XdQuery` by this property.

```kotlin
// Optional nullable String property with database name `description`.
var description by xdBlobStringProp()
```

##### xdRequiredBlobStringProp --- required string blob property
- Property type: `String`.
- If its value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
- Xodus-DNQ checks on flush that property value is defined.
- Xodus stores massive blobs as separate files on disk.
- Xodus also does not build indices for blob properties. So indices for blob properties do not consume memory 
but you cannot filter or sort `XdQuery` by this property.

```kotlin
// Required not-null String property with database name `description`.
var description by xdRequiredBlobStringProp()
```

#### String List
##### xdSetProp --- set of comparables property
- Property type: `Set`.
- If its value is not defined in database the property returns `emptySet()`.
- Xodus builds index for every element of the set. So you can [query entity by any element](queries.md#set-property-contains-value).

```kotlin
// Set of strings property with database name `tags`.
var tags by xdSetProp<XdPost, String>()
```

### Simple property constraints
Property constraints are checked on transaction flush. Xodus-DNQ throws `ConstraintsValidationException` 
if some of them fail. Method `getCauses()` of `ConstraintsValidationException` returns all actual
`DataIntegrityViolationException`s corresponding to data validation errors that happen during the transaction flush.   

```kotlin
try {
    store.transactional {
        // Do some database update           
    }
} catch(e: ConstraintsValidationException) {
    e.causes.forEach {
        e.printStackTrace()
    }
}
``` 

#### Regex
Checks that string property value matches regular expression.

```kotlin
var javaIdentifier by xdStringProp {
    regex(Regex("[A-Za-z][A-Za-z0-9_]*"), "is not a valid Java identifier")
}
```

#### Email
Checks that string property value is a valid email. Optionally accepts custom regular expression to verify email.
```kotlin
var email by xdStringProp { email() }
```

#### None of characters
Checks that string property value contains none of the specified characters. 

```kotlin
var noDash by xdStringProp { containsNone("-") }
```

#### Letters only
Checks that string property value contains only letter characters. 

```kotlin
var alpha by xdStringProp { alpha() }
```

#### Digits only
Checks that string property value contains only digit characters. 

```kotlin
var number by xdStringProp { numeric() }
```

#### Digits and letters only
Checks that string property value contains only digit and letter characters. 

```kotlin
var base64 by xdStringProp { alphaNumeric() }
```

#### URI
Checks that string property value is a valid [URI](https://en.wikipedia.org/wiki/Uniform_Resource_Identifier). 

```kotlin
var uri by xdStringProp { uri() }
```

#### URL
Checks that string property value is a valid [URL](https://en.wikipedia.org/wiki/URL). 

```kotlin
var url by xdStringProp { url() }
```

#### String length
Checks that length of string property value falls into defined range. 

```kotlin
var badPassword by xdStringProp { length(min = 5, max = 10) }
```

#### Require if
Checks that property value is defined if provided closure returns `true`. 

```kotlin
var main by xdStringProp()
var dependent by xdLongProp { requireIf { main != null } }
```

#### Min value 
Checks that number property value is more or equals than given value.

```kotlin
var timeout by xdIntProp { min(1000) }
```

#### Max value 
Checks that number property value is less or equals than given value.

```kotlin
var timeout by xdIntProp { max(10_000) }
```

#### Is after 
Checks that DateTime property value is after given value.

```kotlin
var afterDomini by xdDateTimeProp { isAfter({ domini }) }
```

#### Is before 
Checks that DateTime property value is before given value.

```kotlin
var beforeChrist by xdDateTimeProp { isBefore({ domini }) }
```

#### Future
Checks that DateTime property value is a moment in the future.

```kotlin
var future by xdDateTimeProp { future() }
```

#### Past
Checks that DateTime property value is a moment in the past.

```kotlin
var past by xdDateTimeProp { past() }
```

#### Custom Property Constraints
You can define your own property constrains in the same way built-in constraints are defined. You need to defined
an extension method for `PropertyConstraintBuilder` that builds and adds your constraint.
 
```kotlin
fun PropertyConstraintBuilder<*, String?>.cron(
        message: String = "is not a valid cron expression"
) {
    constraints.add(object : PropertyConstraint<String?>() {

        /**
         * Is called on flush to check if new value of property matches constraint. 
         */
        override fun isValid(value: String?): Boolean {
            // It's better to ignore empty values in your custom checks. 
            // Otherwise check for required property may fail twice 
            // as undefined value and as invalid cron expression. 
            return value == null || value.isEmpty() || try {
                CronExpression(value)
                true
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Is called on check failure to build an exeption error message  
         */
        override fun getExceptionMessage(propertyName: String, propertyValue: String?): String {
            val errorMessage = try {
                CronExpression(propertyValue)
                ""
            } catch (e: Exception) {
                e.message
            }

            return "$propertyName should be valid cron expression but was $propertyValue: $errorMessage"
        }

        override fun getDisplayMessage(propertyName: String, propertyValue: String?) = message
    })
}

```
### Links
There are three types of links: directed links, bi-directed links and aggregation links. 

Most of the methods that create delegates for links accept optional parameter `dbPropertyName`. By default Xodus-DNQ
uses Kotlin-property name to reference the link in Xodus database. Parameter `dbPropertyName` helps to override this.  

#### On delete policy
Most of the methods that create delegates for links accept optional parameters `onDelete` and `onTargetDelete`.
This parameters defines what Xodus-DNQ should do with the link on this entity delete or on the link target delete.
Available options are

|-------------------|-----------------------------------------------------------------------------------|
| `FAIL`            | Fail transaction if entity is deleted but link still points to it.                |
| `CLEAR`           | Clear link to deleted entity.                                                     |
| `CASCADE`         | If entity is delete and link still exists, then delete entity on the opposite link end as well. |
| `FAIL_PER_TYPE`   | Fail transaction with a custom message if entity is deleted but link still points to it. One message per entity type. |
| `FAIL_PER_ENTITY` | Fail transaction with a custom message if entity is deleted but link still points to it.  One message per entity. |

#### Unidirectional assosiations

##### xdLink0_1<XdSource, XdTarget> –-- unidirectional [0..1] association
- Property type: `XdTarget?`.
- If its value is not defined in database the property returns `null`.
- First parameter is companion object of persistent class that is an opposite end of the association.
- Parameter `onDelete` defines what should happen to the entity on the opposite end when this entity is deleted.
  - `CLEAR` (*default*) --- nothing.
  - `CASCADE` --- entity on the opposite end is deleted as well.
- Parameter `onTargetDelete` defines what should happen to this entity when the entity on the opposite end is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. link should be cleared before target entity delete.   
  - `CLEAR` --- link is cleared.
  - `CASCADE` --- this entity is deleted as well.

```kotlin 
var directedOptionalLink by xdLink0_1(XdTarget, onTargetDelete = OnDeletePolicy.CLEAR)
```

##### xdLink1<XdSource, XdTarget> --- unidirectional [1] association
- Property type: `XdTarget`.
- Xodus-DNQ checks on flush that the link points to some entity.
- If its value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
- First parameter is companion object of persistent class that is an opposite end of the association.
- Parameter `onDelete` defines what should happen to the entity on the opposite end when this entity is deleted.
  - `CLEAR` (*default*) --- nothing.
  - `CASCADE` --- entity on the opposite end is deleted as well.
- Parameter `onTargetDelete` defines what should happen to this entity when the entity on the opposite end is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. link should be cleared before target entity delete.   
  - `CASCADE` --- this entity is deleted as well.

```kotlin 
var directedRequiredLink by xdLink1(XdTarget)
```

##### xdLink0_N<XdSource, XdTarget> --- unidirectional [0..N] association
- Property type: `XdMutableQuery<XdTarget>`.
- If its value is not defined in database the property returns `XdTarget.emptyQuery()`.
- First parameter is companion object of persistent class that is an opposite end of the association.
- Parameter `onDelete` defines what should happen to the entities on the opposite end when this entity is deleted.
  - `CLEAR` (*default*) --- nothing.
  - `CASCADE` --- entity on the opposite end is deleted as well.
- Parameter `onTargetDelete` defines what should happen to this entity when one of the entities on the opposite end is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. association with the deleted entity should be removed first.   
  - `CLEAR` --- association with the deleted entity is removed.
  - `CASCADE` --- this entity is deleted as well.

```kotlin 
var users by xdLink0_N(XdUser)
```

##### xdLink1_N<XdSource, XdTarget> --- unidirectional [1..N] association
- Property type: `XdMutableQuery<XdTarget>`.
- If its value is not defined in database the property returns `XdTarget.emptyQuery()`.
- Xodus-DNQ checks on flush that the link contains at least one entity.
- First parameter is companion object of persistent class that is an opposite end of the association.
- Parameter `onDelete` defines what should happen to the entities on the opposite end when this entity is deleted.
  - `CLEAR` (*default*) --- nothing.
  - `CASCADE` --- entity on the opposite end is deleted as well.
- Parameter `onTargetDelete` defines what should happen to this entity when one of the entities on the opposite end is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. association with the deleted entity should be removed first.   
  - `CLEAR` --- association with the deleted entity is removed.
  - `CASCADE` --- this entity is deleted as well.

```kotlin 
var users by xdLink1_N(XdUser)
```

#### Bidirectional associations

For bidirectional associations Xodus-DNQ maintains both ends of the links. For example, if there is a bidirectional
link between `XdUser::groups` and `XdGroup::users`, and you add some group to `user.groups.add(group)` 
Xodus-DNQ will automatically add `user` to `group.users`.

##### xdLink0_1<XdSource, XdTarget> --- bidirectional [0..1] association
- Property type: `XdTarget?`.
- If its value is not defined in database the property returns `null`.
- First parameter is a reference to a property that defines the opposite end of the association.
- Parameter `onDelete` defines what should happen to the entity on the opposite end when this entity is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. link should be cleared before this entity delete.   
  - `CLEAR` --- link is cleared.
  - `CASCADE` --- entity on the opposite end is deleted as well.
- Parameter `onTargetDelete` defines what should happen to this entity when the entity on the opposite end is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. link should be cleared before target entity delete.   
  - `CLEAR` --- link is cleared.
  - `CASCADE` --- this entity is deleted as well.

```kotlin
val group by xdLink0_1(XdGroup::users)
```

##### xdLink1 --- bidirectional [1] association
- Property type: `XdTarget`.
- Xodus-DNQ checks on flush that the link points to some entity.
- If its value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
- First parameter is a reference to a property that defines the opposite end of the association.
- Parameter `onDelete` defines what should happen to the entity on the opposite end when this entity is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. link should be cleared before this entity delete.   
  - `CLEAR` --- link is cleared.
  - `CASCADE` --- entity on the opposite end is deleted as well.
- Parameter `onTargetDelete` defines what should happen to this entity when the entity on the opposite end is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. link should be cleared before target entity delete.   
  - `CASCADE` --- this entity is deleted as well.

```kotlin
val group by xdLink1(XdGroup::users)
```

##### xdLink0_N<XdSource, XdTarget> --- bidirectional [0..N] association
- Property type: `XdMutableQuery<XdTarget>`.
- If its value is not defined in database the property returns `XdTarget.emptyQuery()`.
- First parameter is a reference to a property that defines the opposite end of the association.
- Parameter `onDelete` defines what should happen to the entities on the opposite end when this entity is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. association should be deleted before this entity delete.   
  - `CLEAR` (*default*) --- association is cleared.
  - `CASCADE` --- entities on the opposite end are deleted as well.
- Parameter `onTargetDelete` defines what should happen to this entity when one of the entities on the opposite end is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. association with the deleted entity should be removed first.   
  - `CLEAR` --- association with the deleted entity is removed.
  - `CASCADE` --- this entity is deleted as well.

```kotlin
val groups by xdLink0_N(XdGroup::users)
```

##### xdLink1_N<XdSource, XdTarget> --- bidirectional [1..N] association
- Property type: `XdMutableQuery<XdTarget>`.
- If its value is not defined in database the property returns `XdTarget.emptyQuery()`.
- Xodus-DNQ checks on flush that the link contains at least one entity.
- First parameter is a reference to a property that defines the opposite end of the association.
- Parameter `onDelete` defines what should happen to the entities on the opposite end when this entity is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. association should be deleted before this entity delete.   
  - `CLEAR` --- association is cleared.
  - `CASCADE` --- entities on the opposite end are deleted as well.
- Parameter `onTargetDelete` defines what should happen to this entity when one of the entities on the opposite end is deleted.
  - `FAIL` (*default*) --- transaction fails, i.e. association with the deleted entity should be removed first.   
  - `CLEAR` --- association with the deleted entity is removed.
  - `CASCADE` --- this entity is deleted as well.

```kotlin
val groups by xdLink1_N(XdGroup::users)
```

#### Aggregations
Aggregations or parent-child association are auxiliary type of links with some predefined behavior.
1. If persistent class of an entity has at least one parent link defined, it is considered as a child entity and 
it should have exactly one parent on flush.
2. On parent delete all its children are deleted as well.

##### xdChild0_1<XdParent, XdChild> --- parent end [0..1] of aggregation association
- Property type: `XdChild?`.
- If its value is not defined in database the property returns `null`.
- First parameter is a property reference to a child end of the association.

```kotlin
val profile by xdChild0_1(XdUser::profile)
```

##### xdChild1<XdParent, XdChild> --- parent end [1] of aggregation association
- Property type: `XdChild`.
- Xodus-DNQ checks on flush that the link points to some entity.
- If its value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
- First parameter is a property reference to a child end of the association.

```kotlin
val profile by xdChild1(XdUser::profile)
```

##### xdChildren0_N<XdParent, XdChild> --- parent end [0..N] of aggregation association
- Property type: `XdMutableQuery<XdChild>`.
- If its value is not defined in database the property returns `XdTarget.emptyQuery()`.
- First parameter is a property reference to a child end of the association.

```kotlin
val subGroups by xdChildren0_N(XdGroup::parentGroup)
```

##### xdChildren1_N<XdParent, XdChild> --- parent end [1..N] of aggregation association
- Property type: `XdMutableQuery<XdChild>`.
- If its value is not defined in database the property returns `XdTarget.emptyQuery()`.
- Xodus-DNQ checks on flush that the link contains at least one entity.
- First parameter is a property reference to a child end of the association.

```kotlin
val contacts by xdChildren1_N(XdContact::user)
```

##### xdParent<XdChild, XdParent> --- child end of aggregation association, when only one parent link is defined for persistent class
- Property type: `XdParent`.
- Xodus-DNQ checks on flush that the link points to some entity.
- If its value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
- First parameter is a property reference to a parent end of the association.

```kotlin
val user by xdParent(XdUser::contacts)
```

##### xdMultiParent<XdChild, XdParent> --- child end of aggregation association, when multiple parent links are defined for persistent class
- Property type: `XdTarget?`.
- Xodus-DNQ checks on flush that this entity has exactly one parent link defined.
- If its value is not defined in database the property returns `null`.
- First parameter is a property reference to a parent end of the association.

```kotlin
val parentGroup by xdMultiParent(XdGroup::subGroups)
val parentOfRootGroup by xdMultiParent(XdRoot::rootGroup)
```

### Extension properties

#### Bidirectional links

Bidirectional link can also point to Kotlin extension property on one side.  

```kotlin
class XdUser(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdUser>()
    var login by xdRequiredStringProp(unique = true)
}

class SecretInfo(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<Secret>()
    var info by xdBlobStringProp()
    var user: XdUser by xdLink1(XdUser::secret)
}

// this association will be authomatically registered in XdModel together with SecretInfo type
var XdUser.secret by xdLink1(SecretInfo::user)
```

#### Other properties

Kotlin extension properties can be used for simple properties and links. If extension properties use database 
constraints then they should be registered in XdModel with plugins.

```kotlin
class XdUser(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdUser>()
    var login by xdRequiredStringProp(unique = true)
}

var XdUser.name by xdRequiredStringProp()
var XdUser.boss by xdLink1(XdSuperUser)

XdModel.withPlugins(
        SimpleModelPlugin(listOf(XdUser::name, XdUser::boss))
)
```


### isDefined and getSafe

Required properties and links of cardinality `1` have non-null types in Kotlin. But for new entities
that were not committed yet, the properties can be not defined yet. To check if a property has a value one can use
method `isDefined`. To get a value of such a property safely there is a method `getSafe`.
```kotlin
class XdUser(entity: Entity) : XdEntity(entity) {
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
class XdAPI(entity: Entity) : XdEntity(entity) {
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
class XdAPI(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdAPI>()

    var key by xdRequiredStringProp(unique=true)
    var name by xdRequiredStringProp(unique=true)
}
```

```kotlin
class XdAPI(entity: Entity) : XdEntity(entity) {
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
