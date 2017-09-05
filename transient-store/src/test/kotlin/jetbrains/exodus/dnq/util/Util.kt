/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.dnq.util

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.run
import jetbrains.exodus.entitystore.runReadonly

class Util {
    companion object {
        @JvmStatic
        fun <T> toList(iterable: Iterable<T>): List<T> = iterable.toList()

        @JvmStatic
        fun runTranAsyncAndJoin(store: TransientEntityStore, r: Runnable) {
            val t = Thread(Runnable {
                store.run(r)
            })
            t.start()
            try {
                t.join()
            } catch (e: Exception) {
                throw RuntimeException(e)
            }

        }

        @JvmStatic
        fun runReadonlyTranAsyncAndJoin(store: TransientEntityStore, r: Runnable) {
            val t = Thread(Runnable {
                store.runReadonly(r)
            })
            t.start()
            try {
                t.join()
            } catch (e: Exception) {
                throw RuntimeException(e)
            }

        }
    }
}
