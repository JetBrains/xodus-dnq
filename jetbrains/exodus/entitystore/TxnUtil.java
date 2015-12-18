package jetbrains.exodus.entitystore;

public class TxnUtil {

    private TxnUtil() {
    }

    public static void registerTransation(PersistentEntityStoreImpl store, PersistentStoreTransaction txn) {
        store.registerTransaction(txn);
    }
}
