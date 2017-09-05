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
