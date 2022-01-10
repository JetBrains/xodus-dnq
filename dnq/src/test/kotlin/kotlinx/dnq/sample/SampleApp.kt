/**
 * Copyright 2006 - 2022 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.sample

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.creator.findOrNew
import kotlinx.dnq.query.*
import kotlinx.dnq.simple.email
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.util.initMetaData
import java.io.File

class XdUser(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdUser>()

    var login by xdRequiredStringProp(unique = true, trimmed = true)
    var gender by xdLink0_1(XdGender)
    val contacts by xdChildren0_N(XdContact::owner)

    override fun toString(): String {
        return "$login, ${gender?.presentation ?: "N/A"}, ${contacts.asSequence().joinToString()}"
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

abstract class XdContact(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdContact>()

    var owner: XdUser by xdParent(XdUser::contacts)
    var isVerified by xdBooleanProp()

    abstract fun verify()
}

class XdEmail(entity: Entity) : XdContact(entity) {
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

    val databaseHome = File(System.getProperty("user.home"), "xodus-dnq-sample-app")

    val store = StaticStoreContainer.init(
            dbFolder = databaseHome,
            entityStoreName = "db"
    )

    initMetaData(XdModel.hierarchy, store)

    return store
}

fun main(args: Array<String>) {
    val store = initXodus()

    val user = store.transactional {
        val zecksonLogin = "zeckson"

        val zeckson = XdUser.query(XdUser::login eq zecksonLogin).firstOrNull()

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