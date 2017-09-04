package jetbrains.exodus.database;

public enum EntityChangeType {

    ADD("Add"),
    REMOVE("Remove"),
    UPDATE("Update");

    private String name;

    EntityChangeType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}