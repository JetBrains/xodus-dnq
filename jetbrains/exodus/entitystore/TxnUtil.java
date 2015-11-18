package jetbrains.exodus.entitystore;

public class TxnUtil {

    public static void registerTransation(PersistentEntityStoreImpl store, PersistentStoreTransaction txn) {
        store.registerTransaction(txn);
    }
}
