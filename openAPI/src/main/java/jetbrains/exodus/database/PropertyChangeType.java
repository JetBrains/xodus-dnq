package jetbrains.exodus.database;

public enum PropertyChangeType {

    UPDATE("Update"),
    REMOVE("Remove");

    private String name;

    PropertyChangeType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

}