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
```kotlin
query(node: NodeBase)
filterIsInstance(entityType: XdEntityType<S>)
filterIsNotInstance(entityType: XdEntityType<S>)
```

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

TO BE DONE
```kotlin
    abstract fun add(entity: T)
    abstract fun remove(entity: T)
    abstract fun clear()

addAll(elements: Sequence<T>)
addAll(elements: XdQuery<T>)
addAll(elements: Iterable<T>)

removeAll(elements: Sequence<T>)
removeAll(elements: XdQuery<T>)
removeAll(elements: Iterable<T>)
``` 