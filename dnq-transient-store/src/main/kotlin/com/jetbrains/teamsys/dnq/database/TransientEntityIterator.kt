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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.EntityIterator

/**
 * Date: 12.03.2007
 * Time: 15:10:33
 *
 * @author Vadim.Gurov
 */
internal class TransientEntityIterator(private val iter: Iterator<TransientEntity>) : EntityIterator {

    override fun hasNext() = iter.hasNext()

    override fun next() = iter.next()

    override fun remove() {
        throw UnsupportedOperationException("Remove from iterator is not supported by transient iterator")
    }

    override fun nextId() = iter.next().id

    override fun dispose(): Boolean {
        throw UnsupportedOperationException("Transient iterator does not support disposing.")
    }

    override fun skip(number: Int): Boolean {
        var elementsLeftToSkip = number
        while (elementsLeftToSkip-- > 0 && hasNext()) {
            next()
        }
        return hasNext()
    }

    override fun shouldBeDisposed(): Boolean {
        return false //TODO: revisit EntityIterator interface and remove these stub method
    }
}
