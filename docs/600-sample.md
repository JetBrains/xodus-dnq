---
layout: page
title: Sample Application
---

This is a sample application that covers
1. Persistent classes and enumerations definition
1. Database metamodel initialization
1. Transactions
1. Queries

```kotlin
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.enum.XdEnumEntityType
import kotlinx.dnq.query.*
import kotlinx.dnq.simple.email
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.util.initMetaData
import java.io.File

class XdUser(override val entity: Entity) : XdEntity() {
    companion object : XdNaturalEntityType<XdUser>()

    var login by xdRequiredStringProp(unique = true, trimmed = true)
    var gender by xdLink0_1(XdGender)
    val contacts by xdChildren0_N(XdContact::owner)

    override fun toString(): String {
        return "$login, " +
            "${gender?.presentation ?: "N/A"}, " +
            "${contacts.asSequence().joinToString()}"
    }
}

class XdGender(entity: Entity) : XdEnumEntity(entity) {
    companion object : XdEnumEntityType<XdGender>() {
        val FEMALE by enumField { presentation = "F" }
        val MALE by enumField { presentation = "M" }
        val OTHER by enumField { presentation = "-" }
    }

    var presentation by xdRequiredStringProp()
        private set
}

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

fun initXodus(): TransientEntityStore {
    XdModel.registerNodes(
            XdGender,
            XdUser,
            XdContact,
            XdEmail
    )

    val databaseHome = File(
            System.getProperty("user.home"), 
            "xodus-dnq-sample-app"
    )

    val store = StaticStoreContainer.init(
            dbFolder = databaseHome,
            environmentName = "db"
    )

    initMetaData(XdModel.hierarchy, store)

    return store
}

fun main(args: Array<String>) {
    val store = initXodus()

    val user = store.transactional {
        val zecksonLogin = "zeckson"

        val zeckson = XdUser.all()
                .query(XdUser::login eq zecksonLogin)
                .firstOrNull()

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

    store.transactional(readonly = true) {
        println(user)
    }
}
```