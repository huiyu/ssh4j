package io.github.huiyu.ssh4j;

public enum FileAction {
    // POSIX style
    NONE("---"),
    EXECUTE("--x"),
    WRITE("-w-"),
    WRITE_EXECUTE("-wx"),
    READ("r--"),
    READ_EXECUTE("r-x"),
    READ_WRITE("rw-"),
    ALL("rwx");

    private final static FileAction[] vals = values();

    private final String symbol;

    FileAction(String symbol) {
        this.symbol = symbol;
    }

    public static FileAction get(String permission) {

        for (FileAction fileAction : vals) {
            if (fileAction.symbol.equals(permission)) {
                return fileAction;
            }
        }
        return null;
    }

    public boolean implies(FileAction that) {
        return that != null? (this.ordinal() & that.ordinal()) == that.ordinal() : false;
    }

    public FileAction and(FileAction that) {
        return vals[ordinal() & that.ordinal()];
    }

    public FileAction or(FileAction that) {
        return vals[ordinal() | that.ordinal()];
    }

    public FileAction not() {
        return vals[7 - ordinal()];
    }
}
