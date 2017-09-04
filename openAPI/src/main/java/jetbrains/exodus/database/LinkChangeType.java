package jetbrains.exodus.database;

public enum LinkChangeType {

    ADD("Add"),
    REMOVE("Remove"),
    ADD_AND_REMOVE("AddAndRemove");

    private String name;

    LinkChangeType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

}
