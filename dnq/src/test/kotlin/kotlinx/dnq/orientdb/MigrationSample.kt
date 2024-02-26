/**
 * Copyright 2006 - 2023 JetBrains s.r.o.
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
package kotlinx.dnq.orientdb

import com.orientechnologies.orient.core.db.OrientDB
import com.orientechnologies.orient.core.db.OrientDBConfig
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.metadata.ModelMetaDataImpl
import kotlinx.dnq.*
import kotlinx.dnq.creator.findOrNew
import kotlinx.dnq.query.and
import kotlinx.dnq.query.asSequence
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.query
import kotlinx.dnq.simple.email
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.util.initMetaData
import java.io.File
import java.io.InputStream


class XdUser(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdUser>()

    var login by xdRequiredStringProp(unique = true, trimmed = true)

    var requiredString by xdRequiredStringProp()
    var string by xdStringProp()


    var byte by xdByteProp()
    var requiredByte by xdRequiredByteProp()
    var nullableByte by xdNullableByteProp()

    var short by xdShortProp()
    var requiredShort by xdRequiredShortProp()
    var nullableShort by xdNullableShortProp()

    var int by xdIntProp()
    var requiredInt by xdRequiredIntProp()
    var nullableInt by xdNullableIntProp()

    var long by xdLongProp()
    var requiredLong by xdRequiredLongProp()
    var nullableLong by xdNullableLongProp()
    
    var float by xdFloatProp()
    var requiredFloat by xdFloatProp()
    var nullableFloat by xdNullableFloatProp()

    var double by xdDoubleProp()
    var requiredDouble by xdDoubleProp()
    var nullableDouble by xdNullableDoubleProp()

    var dataTime by xdDateTimeProp()
    var requiredDataTime by xdRequiredDateTimeProp()

    var set: Set<String> by xdSetProp<XdUser, String>()
    val mutableSet: MutableSet<Long> by xdMutableSetProp<XdUser, Long>()

    var blob: InputStream? by xdBlobProp()
    var requiredBlob: InputStream by xdRequiredBlobProp()

    var blobString: String? by xdBlobStringProp()
    var requiredBlobString: String by xdRequiredBlobStringProp()

    var gender by xdLink0_1(XdGender::users)
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

    val users by xdLink0_N(XdUser)
}

abstract class XdContact(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdContact>()

    var owner: XdUser by xdParent(XdUser::contacts)
    var isVerified: Boolean by xdBooleanProp()

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

fun initXodus(): TransientEntityStore {
    XdModel.registerNodes(
        XdGender,
        XdUser,
        XdContact,
        XdEmail
    )

    val databaseHome = File(System.getProperty("user.home"), "xodus-dnq-sample-app")
    println(databaseHome)

    val store = StaticStoreContainer.init(
        dbFolder = databaseHome,
        entityStoreName = "db"
    )

    initMetaData(XdModel.hierarchy, store)

    return store
}

fun main(args: Array<String>) {
    val store = initXodus()
    val dnqModel = store.modelMetaData as ModelMetaDataImpl

    val username = "opca"
    val userPassword = "drista"
    val dbName = "tmpDb"

    OrientDB("memory", OrientDBConfig.defaultConfig()).use { orientDb ->
        orientDb.execute("create database $dbName MEMORY users ( $username identified by '$userPassword' role admin )")

        orientDb.open("tmpDb", username, userPassword).use { session ->
            val schemaApplier = DnqSchemaToOrientDB(dnqModel, XdModel.hierarchy, session)
            schemaApplier.apply()
        }
    }
}



