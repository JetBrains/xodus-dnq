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

/**
 * User: Maxim Mazin
 */
object OrderedAssociationSemantics {

    @JvmStatic
    fun compare(left: Entity?, right: Entity?, orderPropertyName: String, cmp: Int): Boolean {
        val leftOrder = left?.reattachTransient()?.getProperty(orderPropertyName)
        val rightOrder = right?.reattachTransient()?.getProperty(orderPropertyName)

        val cmpResult = when {
            leftOrder != null && rightOrder != null -> leftOrder.compareTo(rightOrder)
            leftOrder != null && rightOrder == null -> 1
            leftOrder == null && rightOrder != null -> -1
            else -> 0
        }

        return when (cmp) {
            1 -> cmpResult >= 0
            2 -> cmpResult < 0
            3 -> cmpResult <= 0
            else -> cmpResult > 0
        }
    }

    @JvmStatic
    fun swap(left: Entity?, right: Entity?, orderPropertyName: String) {
        val txnLeft = left?.reattachTransient()
        val txnRight = right?.reattachTransient()

        if (txnLeft != null && txnRight != null) {
            val leftOrder = txnLeft.getProperty(orderPropertyName)
            val rightOrder = txnRight.getProperty(orderPropertyName)

            if (leftOrder != null && rightOrder != null) {
                txnLeft.setProperty(orderPropertyName, rightOrder)
                txnRight.setProperty(orderPropertyName, leftOrder)
            }
        }
    }
}
