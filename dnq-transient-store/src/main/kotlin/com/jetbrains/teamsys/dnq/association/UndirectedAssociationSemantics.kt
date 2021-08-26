/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package com.jetbrains.teamsys.dnq.association

import com.jetbrains.teamsys.dnq.database.reattachTransient
import com.jetbrains.teamsys.dnq.database.threadSessionOrThrow
import jetbrains.exodus.entitystore.Entity

/**
 * Implements undirected associations management.
 * - 1-1: project.[1]leader <-> user.[1]leaderInProject
 * - 1-n: project[0..n].issues <-> issue[1].project
 * - n-n: project[0..n].assignees <-> user[0..n].assigneeInProjects
 */
object UndirectedAssociationSemantics {

    /**
     * 1. e1.e1Toe2LinkName = e2 <==> e2.e2Toe1LinkName = e1;
     * 2. e2.e2Toe1LinkName = null <==> e1.e1Toe1LinkName = null
     */
    @JvmStatic
    fun setOneToOne(e1: Entity?, e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity?) {
        val txnEntity1 = e1?.reattachTransient()
        val txnEntity2 = e2?.reattachTransient()

        when {
            txnEntity1 != null -> txnEntity1.setOneToOne(e1Toe2LinkName, e2Toe1LinkName, txnEntity2)
            txnEntity2 != null -> txnEntity2.setOneToOne(e2Toe1LinkName, e1Toe2LinkName, txnEntity1)
            else -> throw IllegalArgumentException("Both entities can't be null")
        }
    }

    /**
     * one.oneToManyLinkName.add(many)
     */
    @JvmStatic
    fun createOneToMany(one: Entity, oneToManyLinkName: String, manyToOneLinkName: String, many: Entity) {
        val session = one.threadSessionOrThrow
        val txnOne = one.reattachTransient(session)
        val txnMany = many.reattachTransient(session)

        txnMany.setManyToOne(manyToOneLinkName, oneToManyLinkName, txnOne)
    }

    /**
     * one.oneToManyLinkName.remove(many)
     */
    @JvmStatic
    fun removeOneToMany(one: Entity, oneToManyLinkName: String, manyToOneLinkName: String, many: Entity) {
        val session = one.threadSessionOrThrow
        val txnOne = one.reattachTransient(session)
        val txnMany = many.reattachTransient(session)

        txnOne.removeOneToMany(manyToOneLinkName, oneToManyLinkName, txnMany)
    }

    /**
     * one.oneToManyLinkName.clear
     */
    @JvmStatic
    fun clearOneToMany(one: Entity, oneToManyLinkName: String, manyToOneLinkName: String) {
        val txnOne = one.reattachTransient()
        txnOne.clearOneToMany(manyToOneLinkName, oneToManyLinkName)
    }

    /**
     * many.manyToOneLinkName = one
     * many.manyToOneLinkName = null
     */
    @JvmStatic
    fun setManyToOne(one: Entity?, oneToManyLinkName: String, manyToOneLinkName: String, many: Entity) {
        val session = many.threadSessionOrThrow
        val txnOne = one?.reattachTransient(session)
        val txnMany = many.reattachTransient(session)

        txnMany.setManyToOne(manyToOneLinkName, oneToManyLinkName, txnOne)
    }

    /**
     * e1.e1Toe2LinkName.add(e2) <==> e2.e2Toe1LinkName.add(e1)
     */
    @JvmStatic
    fun createManyToMany(e1: Entity, e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity) {
        val session = e1.threadSessionOrThrow
        val txnEntity1 = e1.reattachTransient(session)
        val txnEntity2 = e2.reattachTransient(session)

        txnEntity1.createManyToMany(e1Toe2LinkName, e2Toe1LinkName, txnEntity2)
    }

    /**
     * e1.e1Toe2LinkName.remove(e2) <==> e2.e2Toe1LinkName.remove(e1)
     */
    @JvmStatic
    fun removeManyToMany(e1: Entity, e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity) {
        // reattach is inside of removeToMany
        DirectedAssociationSemantics.removeToMany(e1, e1Toe2LinkName, e2)
        DirectedAssociationSemantics.removeToMany(e2, e2Toe1LinkName, e1)
    }

    /**
     * e1.e1Toe2LinkName.clear <==> e2.e2Toe1LinkName.clear
     */
    @JvmStatic
    fun clearManyToMany(e1: Entity, e1Toe2LinkName: String, e2Toe1LinkName: String) {
        val txnEntity1 = e1.reattachTransient()
        txnEntity1.clearManyToMany(e1Toe2LinkName, e2Toe1LinkName)
    }

}
