package jetbrains.exodus.env;

import org.jetbrains.annotations.NotNull;

public class EnvironmentUtil {

    private EnvironmentUtil() {
    }

    public static void downgradeTransaction(@NotNull final TransactionImpl txn) {
        if (txn.isExclusive()) {
            final EnvironmentImpl env = txn.getEnvironment();
            env.downgradeTransaction(txn);
            txn.setExclusive(false);
        }
    }
}
