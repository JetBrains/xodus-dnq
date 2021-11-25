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
 * Implements aggregation associations management.&lt;p&gt;
 * - 1-1: project.[1]leader &lt;-&gt; user.[1]leaderInProject &lt;p&gt;
 * - 1-n: project[0..n].issues &lt;-&gt; issue[1].project &lt;p&gt;
 * - n-n: project[0..n].assignees &lt;-&gt; user[0..n].assigneeInProjects &lt;p&gt;
 */
object AggregationAssociationSemantics {

    /**
     * 1. parent.parentToChild = child &lt;==&gt; child.childToParent = parent
     * 2. parent.parentToChild = null &lt;==&gt; child.childToParent = null
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     * @param childToParentLinkName child to parent link name
     * @param child                 child
     */
    @JvmStatic
    fun setOneToOne(parent: Entity?, parentToChildLinkName: String, childToParentLinkName: String, child: Entity?) {
        val txnParent = parent?.reattachTransient()
        val txnChild = child?.reattachTransient()

        when {
            txnParent != null && txnChild != null -> txnParent.setChild(parentToChildLinkName, childToParentLinkName, txnChild)
            txnParent != null && txnChild == null -> txnParent.removeChild(parentToChildLinkName, childToParentLinkName)
            txnParent == null && txnChild != null -> txnChild.removeFromParent(parentToChildLinkName, childToParentLinkName)
            txnParent == null && txnChild == null -> throw IllegalArgumentException("Both entities can't be null.")
        }
    }

    /**
     * parent.parentToChild.add(child)
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     * @param childToParentLinkName child to parent link name
     * @param child                 child
     */
    @JvmStatic
    fun createOneToMany(parent: Entity, parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        val session = parent.threadSessionOrThrow
        val txnParent = parent.reattachTransient(session)
        val txnChild = child.reattachTransient(session)

        txnParent.addChild(parentToChildLinkName, childToParentLinkName, txnChild)
    }

    /**
     * parent.parentToChild.remove(child)
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     * @param childToParentLinkName child to parent link name
     * @param child                 child
     */
    @JvmStatic
    fun removeOneToMany(parent: Entity, parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        val session = parent.threadSessionOrThrow
        val txnChild = child.reattachTransient(session)

        txnChild.removeFromParent(parentToChildLinkName, childToParentLinkName)
    }

    /**
     * parent.parentToChild.clear
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     */
    @JvmStatic
    fun clearOneToMany(parent: Entity, parentToChildLinkName: String) {
        val txnParent = parent.reattachTransient()
        txnParent.clearChildren(parentToChildLinkName)
    }

    /**
     * child.childToParent = parent
     * child.childToParent = null
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     * @param childToParentLinkName child to parent link name
     * @param child                 child
     */
    @JvmStatic
    fun setManyToOne(parent: Entity?, parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        val session = child.threadSessionOrThrow
        val txnParent = parent?.reattachTransient(session)
        val txnChild = child.reattachTransient(session)

        if (txnParent == null) {
            txnChild.removeFromParent(parentToChildLinkName, childToParentLinkName)
        } else {
            // child.childToParent = parent
            txnParent.addChild(parentToChildLinkName, childToParentLinkName, txnChild)
        }
    }

    @JvmStatic
    fun getParent(child: Entity): Entity? {
        val txnChild = child.reattachTransient()
        return txnChild.parent
    }

}
