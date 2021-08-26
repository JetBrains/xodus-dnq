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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.entitystore.Entity

/**
 * Creates error message by incoming Entity and EntityIterable
 */
interface MessageBuilder {

    /**
     * @param linkedEntities - entities which have outgoing link with target
     * @param _this - entity we are trying to delete
     */
    fun build(linkedEntities: @JvmSuppressWildcards Iterable<Entity>?, _this: Entity?, hasMore: Boolean): String
}
