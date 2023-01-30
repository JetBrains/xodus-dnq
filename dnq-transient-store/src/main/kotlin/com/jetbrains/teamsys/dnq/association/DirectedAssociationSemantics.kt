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
package com.jetbrains.teamsys.dnq.association

import com.jetbrains.teamsys.dnq.database.reattachTransient
import jetbrains.exodus.entitystore.Entity


object DirectedAssociationSemantics {

    /**
     * user.role = role
     * user.role = null
     */
    @JvmStatic
    fun setToOne(source: Entity?, linkName: String, target: Entity?) {
        val txnSource = source?.reattachTransient()
        val txnTarget = target?.reattachTransient()
        txnSource?.setToOne(linkName, txnTarget)
    }

    /**
     * project.users.add(user)
     */
    @JvmStatic
    fun createToMany(source: Entity?, linkName: String, target: Entity?) {
        val txnSource = source?.reattachTransient()
        val txnTarget = target?.reattachTransient()
        if (txnTarget != null) {
            txnSource?.addLink(linkName, txnTarget)
        }
    }

    /**
     * project.users.remove(user)
     */
    @JvmStatic
    fun removeToMany(source: Entity?, linkName: String, target: Entity?) {
        val txnSource = source?.reattachTransient()
        val txnTarget = target?.reattachTransient()
        if (txnTarget != null) {
            txnSource?.deleteLink(linkName, txnTarget)
        }
    }

    /**
     * project.users.clear
     */
    @JvmStatic
    fun clearToMany(source: Entity?, linkName: String) {
        val txnSource = source?.reattachTransient()
        txnSource?.deleteLinks(linkName)
    }

}
