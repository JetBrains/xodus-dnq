/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
package kotlinx.dnq.linkConstraints


import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.toList
import org.junit.Test
import kotlin.test.assertFailsWith


class OnDeleteConstraintTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(
                D0, D1, D2,
                FThread, Message
        )
    }

    class D0(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<D0>()

        val toD1 by xdLink0_N(D1::toD0, onDelete = OnDeletePolicy.CASCADE)
        val toD2 by xdLink0_N(D2::toD0, onDelete = OnDeletePolicy.CLEAR)
    }

    class D1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<D1>()

        val toD0: XdMutableQuery<D0> by xdLink0_N(D0::toD1)
    }

    class D2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<D2>()

        val toD0: XdMutableQuery<D0> by xdLink0_N(D0::toD2)
    }

    @Test
    fun undirectedManyToManyOnDeleteCascade() {
        // association end type      : Undirected
        // to target end constraint  : OnDelete(cascade)
        // to target end cardinality : [..n]
        // to source end constraint  : ---
        // to source end cardinality : [..n]
        // result                    :  suspended incoming links for D0 instances
        val d0 = store.transactional {
            val d0 = D0.new()
            val d1 = D1.new()

            d0.toD1.add(d1)
            d0.toD1.add(D1.new())
            d0.toD1.add(D1.new())
            d0.toD1.add(D1.new())

            d1.toD0.add(D0.new())
            d1.toD0.add(D0.new())
            d1.toD0.add(D0.new())
            d0
        }
        assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                d0.delete()
            }
        }
    }

    @Test

    fun undirectedManyToManyOnDeleteClear() {
        // association end type      : Undirected
        // to target end constraint  : OnDelete(clear)
        // to target end cardinality : [..n]
        // to source end constraint  : ---
        // to source end cardinality : [..n]
        // result                    : associations with d0 are removed everywhere
        val (d0, d2) = store.transactional {
            val d0 = D0.new()
            val d2 = D2.new()

            d0.toD2.add(d2)
            d0.toD2.add(D2.new())
            d0.toD2.add(D2.new())
            d0.toD2.add(D2.new())

            d2.toD0.add(D0.new())
            d2.toD0.add(D0.new())
            d2.toD0.add(D0.new())
            Pair(d0, d2)
        }
        store.transactional {
            d0.delete()
        }
        store.transactional {
            assertThat(D0.all().toList()).hasSize(3)
            assertThat(D2.all().toList()).hasSize(4)
            assertThat(d2.toD0.toList()).hasSize(3)
        }
    }

    class FThread(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FThread>()

        var rootMessage by xdChild1(Message::ownerThread)
        val messages by xdLink0_N(Message::thread, onDelete = OnDeletePolicy.CASCADE)
    }

    class Message(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Message>()

        var thread: FThread? by xdLink0_1(FThread::messages, onDelete = OnDeletePolicy.CLEAR)
        var ownerThread: FThread? by xdMultiParent(FThread::rootMessage)
        val childMessage by xdChildren0_N(Message::parentMessage)
        var parentMessage: Message? by xdMultiParent(Message::childMessage)
    }

    @Test
    fun doublePathLinkedEntities() {
        // Entity thread is connected to doubleLinkedMessage with direct link "Fthread.messages" and through
        // rootMessage entity, i.e. with links FThread.rootMessage + Message.childMessage. Entity thread deletion
        // has to initiate correct deletion of all messages
        // result                    : all messages are deleted
        val (thread, rootMessage) = store.transactional {
            val thread = FThread.new()
            val rootMessage = Message.new()
            thread.rootMessage = rootMessage
            thread.messages.add(rootMessage)

            val doubleLinkedMessage = Message.new()
            rootMessage.childMessage.add(doubleLinkedMessage)
            thread.messages.add(doubleLinkedMessage)

            for (i in 0..99) {
                val message = Message.new()
                rootMessage.childMessage.add(message)
                thread.messages.add(message)
            }
            Pair(thread, rootMessage)
        }
        store.transactional {
            assertThat(rootMessage.childMessage.toList()).hasSize(101)
            assertThat(thread.messages.toList()).hasSize(102)
        }
        store.transactional {
            thread.delete()
        }
        store.transactional {
            assertThat(Message.all().toList()).isEmpty()
        }
    }
}
