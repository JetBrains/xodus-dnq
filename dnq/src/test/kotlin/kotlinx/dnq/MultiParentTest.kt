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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import org.junit.Test

class MultiParentTest : DBTest() {

    class Message(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Message>()


        var messageContent by xdChild1(MessageContent::message)
        var editedMessageContent by xdChild0_1(MessageContent::editedMessage)
    }


    class MessageContent(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<MessageContent>()

        var message: Message? by xdMultiParent(Message::messageContent)
        var editedMessage: Message? by xdMultiParent(Message::editedMessageContent)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Message, MessageContent)
    }

    @Test
    fun changeParent() {
        val (message, messageContent) = transactional {
            val message = Message.new()
            val messageContent = MessageContent.new()
            message.messageContent = messageContent
            Pair(message, messageContent)
        }
        transactional {
            assertQuery(MessageContent.all()).containsExactly(messageContent)
        }
        val editedMessageContent = transactional {
            val editedMessageContent = MessageContent.new()
            message.editedMessageContent = editedMessageContent
            editedMessageContent
        }
        transactional {
            assertQuery(MessageContent.all()).containsExactly(messageContent, editedMessageContent)
        }
        transactional {
            message.messageContent = editedMessageContent
        }
        transactional {
            assertQuery(MessageContent.all()).containsExactly(editedMessageContent)
            assertThat(messageContent.isRemoved).isTrue()
        }
    }
}
