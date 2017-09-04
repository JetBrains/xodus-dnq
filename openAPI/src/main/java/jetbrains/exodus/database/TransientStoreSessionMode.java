package jetbrains.exodus.database;

public enum TransientStoreSessionMode {

    inplace("inplace"),
    deferred("deferred");

    private final String name;

    TransientStoreSessionMode(final String name) {
        this.name = name;
    }


    public String toString() {
        return name;
    }
}
