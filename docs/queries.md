---
layout: page
title: Data Query 
---

## Transactions
All DB-operations should happen in transactions. XdEntities from one transaction can be safely used
in another one, i.e. one can store a reference to an entity outside a transaction.

## New entities

```kotlin
class XdUser(override val entity: Entity) : XdEntity() {
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
TO BE DONE

### Find or Create
TO BE DONE

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

It is possible to entities by property of the property.
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