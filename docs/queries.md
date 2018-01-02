---
layout: page
title: Data Query 
---

## Transactions
All DB-operations should happen in transactions. XdEntities from one transaction can be safely used
in another one, i.e. one can store a reference to an entity outside a transaction.

## New entities

```kotlin
class XdUser(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdUser>()

    var login by xdRequiredStringProp(unique = true)
}

fun createUser(store: TransientEntityStore, login: String): XdUser {
    return store.transactional {
        XdUser.new {
            this.login = login 
        }
    }
}
```

## XdQuery

Effective database collections that use Xodus indices are represented in Xodus DNQ by objects of type `XdQuery<XD>`.
Such objects are returned by `XdEntityType#all()`, [multi-value persistent links](properties.md#links), and various
database collection operations: filtering, sorting, mapping, etc.

### Query all entities
```kotlin
XdUser.all()
```

### Empty query
```kotlin
XdUser.emptyQuery()
```

### Convert to Kotlin collections
There are several extension functions to convert `XdQueries` to Kotlin-collections. There is also 
an extension function operator `iterator`, that enables usage of `XdQuery` in for-in loop.

```kotlin
XdUser.all().iterator()
XdUser.all().asSequence()
XdUser.all().toCollection(ArrayList())
XdUser.all().toList()
XdUser.all().toMutableList()
XdUser.all().toSet()
XdUser.all().toHashSet()
XdUser.all().toSortedSet(compareBy { it.login })
XdUser.all().toMutableSet()
```

### Query of specified elements
```kotlin
XdUser.queryOf(user)
``` 

### Filter

Xodus-DNQ provides methods for entity filtering using built-in Xodus indices. It's much more efficient and 
performant than in-memory collection manipulations.

```kotlin
// Get all users with skill greater than 2
XdUser.all().query(XdUser::skill gt 2)
XdUser.all().filter { it.skill gt 2 }

// The same but shorter
XdUser.query(XdUser::skill gt 2)
XdUser.filter { it.skill gt 2 }
```

```kotlin
// Contacts that have type XdEmailContact
user.contacts.filterIsInstance(XdEmailContact)

// Contacts that have any type but XdEmailContact
user.contacts.filterIsNotInstance(XdEmailContact)
```

Method `query()` accepts an object of type `NodeBase`. This object defines an abstract syntax tree of filtering 
operation expression. There is a set of predefined methods to build such trees. 

Method `filter()` accepts function which will be called on a "template" entity. Syntax tree of filtering operations 
will be generated according to properties and links requested from "template" entity. As a result `filter` function 
should access **only database backing fields of XdEntity**.  


#### Equals

Filter entities with a value of the property equal to the given value.  

```kotlin
// Users with login "root"
XdUser.query(XdUser::login eq "root")
XdUser.filter { it.login eq "root" }

// Users with gender equal to XdGender.FEMALE
XdUser.query(XdUser::gender eq XdGender.FEMALE)
XdUser.filter { it.gender eq XdGender.FEMALE }
```

#### Not Equals

Filter entities with a value of the property not equal to the given value.  

```kotlin
// Users with any login but "root"
XdUser.query(XdUser::login ne "root")
XdUser.filter { it.login ne "root" }

// Users with gender not equal to XdGender.FEMALE
XdUser.query(XdUser::gender ne XdGender.FEMALE)
XdUser.filter { it.gender ne XdGender.FEMALE }
```

#### Greater than

Filter entities with a value of the property greater than given `value`.  

```kotlin
// Users with skill greater than 2
XdUser.query(XdUser::skill gt 2)
XdUser.filter { it.skill gt 2 }
```

#### Less than

Filter entities with a value of the property less than given `value`.  

```kotlin
// Users with skill less than 2
XdUser.query(XdUser::skill lt 2)
XdUser.filter {it.skill lt 2 }
```

#### Greater or equal

Filter entities with a value of the property greater or equal to given `value`.  

```kotlin
// Users with skill greater or equal to 2
XdUser.query(XdUser::skill ge 2)
XdUser.filter { it.skill ge 2 }
```

#### Less or equal

Filter entities with a value of the property less or equal to given `value`.  

```kotlin
// Users with skill less or equal to 2
XdUser.query(XdUser::skill le 2)
XdUser.filter { it.skill le 2 }
```

#### Starts with

Filter entities with a value of the String property starting with the given `value`.  

```kotlin
// Users with skill less than 2
XdUser.query(XdUser::login startsWith "max")
XdUser.filter { it.login startsWith "max" }
```

#### Contains

Filter entities where one of the values of the multi-value property contains the given value.

```kotlin
// Users that have `group` among their groups.
XdUser.query(XdUser::groups contains group)
XdUser.filter { it.groups contains group }
```

#### Value in range

Filter entities with a value of the property matching the given range.  

```kotlin
// Users with skill within the range 1..10
XdUser.query((1..10) contains XdUser::skill)
```

#### Set property contains value

Filter entities with value of set property containing the given value.

```kotlin
class XdPost(entity: Entity): XdEntity(entity) {
  companion object: XdNaturalEntityType<XdPost>()
  
  var tags by xdSetProp<XdPost, String>()
}

XdPost.query(XdPost::tags contains "Kotlin")
```

#### Set element starts with

Filter entities with some element of set property starting with the given value.

```kotlin
class XdPost(entity: Entity): XdEntity(entity) {
  companion object: XdNaturalEntityType<XdPost>()
  
  var tags by xdSetProp<XdPost, String>()
}

XdPost.query(XdPost::tags anyStartsWith "kot")
```

#### Not 

Negation of the given operation.

```kotlin
// Users with any login but "root"
XdUser.query(not(XdUser::login eq "root"))
```

#### And

Conjunction of the given operations.

```kotlin
// Users with any login not equal to "root" and skill more than 2. 
XdUser.query((XdUser::login ne "root") and (XdUser::skill gt 2))
XdUser.filter { (it.login ne "root") and (it.skill gt 2) }
```

#### Or

Disjunction of the given operations.

```kotlin
// Users with login equal to "root" or skill more than 2. 
XdUser.query((XdUser::login eq "root") or (XdUser::skill gt 2))
XdUser.filter { (it.login eq "root") or (it.skill gt 2) }
```

#### Filter by property of property

If you need to filter entities not by the value of their properties but by the value of a property of their property, 
you may use `link` operator. Note that this operation is less effective than direct filtering by the value of 
a property.

```kotlin
// Users with verified contacts
XdUser.query(XdUser::contact.link(XdContact::isVerified eq true))
XdUser.filter { it.contact.isVerified eq true }
```
 
### Find or Create

It's possible to create an entity with a guarantee that no identical entity will be created in a parallel thread.
It's quite handy if you need to create an instance of association class. Method `findOrNew` 

```kotlin
class XdPerson(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdPerson>()

    fun setSkillLevel(skill: XdSkill, level: Int) { 
        val competence = XdCompetence.findOrNew(
                XdCompetence.query(
                        (XdCompetence::person eq this) and (XdCompetence::skill eq skill)
                )
        ) {
            this.person = this@XdPerson
            this.skill = skill
        }
        competence.level = level
    }

    fun anotherWayToSetSkillLevel(skill: XdSkill, level: Int) { 
        // in this version only database backing fields of XdCompetence should be used in assignments in lambda
        val competence = XdCompetence.findOrNew {
            this.person = this@XdPerson
            this.skill = skill
        }
        competence.level = level
    }
}

class XdSkill(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdSkill>()
}

class XdCompetence(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdCompetence>() {
        override val compositeIndices
            get() = listOf(
                    listOf(XdCompetence::person, XdCompetence::skill)
            )
    }

    var person by xdLink1(XdPerson)
    var skill by xdLink1(XdSkill)
    var level by xdIntProp()
}
```

### Query operations

#### Intersect
```kotlin
XdUser.query(XdUser::gender eq XdGender.FEMALE) intersect XdUser.query(XdUser::skill gt 2) 
```

#### Union
```kotlin
XdUser.query(XdUser::gender eq XdGender.FEMALE) union XdUser.query(XdUser::skill gt 2)
XdUser.query(XdUser::gender eq XdGender.FEMALE) union user 
```

#### Concatenation
```kotlin
XdUser.query(XdUser::gender eq XdGender.FEMALE) plus XdUser.query(XdUser::skill gt 2)
XdUser.query(XdUser::gender eq XdGender.FEMALE) plus user 
```

#### Exclusion
```kotlin
XdUser.query(XdUser::gender eq XdGender.FEMALE) exclude XdUser.query(XdUser::skill le 2) 
XdUser.query(XdUser::gender eq XdGender.FEMALE) exclude user 
```

### Sort
Sorting operations create new `XdQuery` and does not alter their arguments. Sorting is stable, i.e. it is possible
to sort first by one property, then by another, equal values of the second sorting will keep their order according
to the first sorting.

```kotlin
// Sort all users by gender, users of the same gender sort by login 
XdUser.all().sortedBy(XdUser::login).sortedBy(XdUser::gender, asc = true)
```

It is possible to sort entities by property of the property.
```kotlin
// Sort users by titles of their jobs
XdUser.all().sortedBy(XdUser::job, XdJob::title)
```

### Map

You can convert a query of entities to query of some link values. 
```kotlin
// Get jobs of existing users
XdUser.all().mapDistinct(XdUser::job)
```

It also works for multi-value queries as well.
```kotlin
// Get groups of existing users
XdUser.all().flatMapDistinct(XdUser::groups)
```

### Size

```kotlin
// Calculate exact number of users
XdUser.all().size()

// Calculate rough number of users. Executes faster than size() but is eventually accurate
XdUser.all().roughSize()

// Calculate number of users matching the predicate
XdUser.all().size(XdUser::skill gt 2)
```

### isEmpty, isNotEmpty, any, none

The following three expressions do the same: check if there is no users with skill greater than 2
```kotlin
XdUser.all().query(XdUser::skill gt 2).isEmpty()
XdUser.all().query(XdUser::skill gt 2).none()
XdUser.all().none(XdUser::skill gt 2)
```

The following three expressions do the same: check if there are users with skill less or equal to 2
```kotlin
XdUser.all().query(XdUser::skill le 2).isNotEmpty()
XdUser.all().query(XdUser::skill le 2).any()
XdUser.all().any(XdUser::skill le 2)
```

### Paging: drop & take

Query all users except first 5. 
```kotlin
XdUser.all().drop(5)
```

Query at most 5 first users.
```kotlin
XdUser.all().take(5)
```

### indexOf

Calculate position of the element within the query. 
```kotlin
XdUser.all().sortedBy(XdUser::login).indexOf(user)
```
### Contains

Check if the query contains the element.
```kotlin
XdUser.query(XdUser::skill gt 2).contains(user)
user in XdUser.query(XdUser::skill gt 2)
```

### First & single element

Get first element of the query.
```kotlin
XdUser.query(XdUser::skill gt 2).first()
```

Get first element of the query that matches the predicate.
```kotlin
XdUser.all().first(XdUser::skill gt 2)
```

Get first element of the query or return null if the query is empty.
```kotlin
XdUser.query(XdUser::skill gt 2).firstOrNull()
```

Get first element of the query that matches the predicate or return null if the query is empty.
```kotlin
XdUser.all().firstOrNull(XdUser::skill gt 2)
```

Check that there is only one element in the query and return it.
```kotlin
XdUser.query(XdUser::skill gt 2).single()
```

Check that there is only one element matching the predicate and return it.
```kotlin
XdUser.all().single(XdUser::skill gt 2)
```

If there is only one element in the query return it, otherwise return null.
```kotlin
XdUser.query(XdUser::skill gt 2).singleOrNull()
```

If there is only one element matching the predicate return it, otherwise return null.
```kotlin
XdUser.all().singleOrNull(XdUser::skill gt 2)
```

### Mutable queries

Persistent properties representing links have type `XdMutableQuery`. This type has additional operations to modify
value of the properties.

#### Add

```kotlin
user.groups.add(group)
```

#### Remove

```kotlin
user.groups.remove(group)
```

#### Add all

```kotlin
user.groups.addAll(sequenceOf(group1, group2))
user.groups.addAll(listOf(group1, group2))
user.groups.addAll(XdGroup.query(XdGroup::parent eq null))
```

#### Remove all

```kotlin
user.groups.removeAll(sequenceOf(group1, group2))
user.groups.removeAll(listOf(group1, group2))
user.groups.removeAll(XdGroup.query(XdGroup::parent eq null))
```

#### Clear

```kotlin
user.groups.clear()
```
