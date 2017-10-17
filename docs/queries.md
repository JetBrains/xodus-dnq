### Transactions
All DB-operations should happen in transactions. XdEntities from one transaction can be safely used
in another one, i.e. one can store a reference to an entity outside a transaction.

### New

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

### Find or Create
TO BE DONE

### Filter
TO BE DONE

### Order
TO BE DONE

### Map
TO BE DONE

### Convert to Kotlin Collections
TO BE DONE
