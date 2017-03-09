package io.github.huiyu.ssh4j;

public class FilePermission {

    static final int S_ISUID = 04000; // set user ID on execution
    static final int S_ISGID = 02000; // set group ID on execution
    static final int S_ISVTX = 01000; // sticky bit   ****** NOT DOCUMENTED *****

    static final int S_IRUSR = 00400; // read by owner
    static final int S_IWUSR = 00200; // write by owner
    static final int S_IXUSR = 00100; // execute/search by owner
    static final int S_IRGRP = 00040; // read by group
    static final int S_IWGRP = 00020; // write by group
    static final int S_IXGRP = 00010; // execute/search by group
    static final int S_IROTH = 00004; // read by others
    static final int S_IWOTH = 00002; // write by others
    static final int S_IXOTH = 00001; // execute/search by others

    private static final FileAction[] FS_ACTION_VALUES = FileAction.values();

    protected int flag;
    private FileAction userAction;
    private FileAction groupAction;
    private FileAction otherAction;

    public FilePermission(int n) {
        this.flag = n;
        FileAction[] v = FS_ACTION_VALUES;
        set(v[(n >>> 6) & 7], v[(n >>> 3) & 7], v[n & 7]);
    }

    private void set(FileAction u, FileAction g, FileAction o) {
        userAction = u;
        groupAction = g;
        otherAction = o;
    }

    public FileAction getUserAction() {
        return userAction;
    }


    public FileAction getGroupAction() {
        return groupAction;
    }

    public FileAction getOtherAction() {
        return otherAction;
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer(10);

        if ((flag & S_IRUSR) != 0) {
            buf.append('r');
        } else {
            buf.append('-');
        }

        if ((flag & S_IWUSR) != 0) {
            buf.append('w');
        } else {
            buf.append('-');
        }

        if ((flag & S_ISUID) != 0) {
            buf.append('s');
        } else if ((flag & S_IXUSR) != 0) {
            buf.append('x');
        } else {
            buf.append('-');
        }

        if ((flag & S_IRGRP) != 0) {
            buf.append('r');
        } else {
            buf.append('-');
        }

        if ((flag & S_IWGRP) != 0) {
            buf.append('w');
        } else {
            buf.append('-');
        }

        if ((flag & S_ISGID) != 0) {
            buf.append('s');
        } else if ((flag & S_IXGRP) != 0) {
            buf.append('x');
        } else {
            buf.append('-');
        }

        if ((flag & S_IROTH) != 0) {
            buf.append('r');
        } else {
            buf.append('-');
        }

        if ((flag & S_IWOTH) != 0) {
            buf.append('w');
        } else {
            buf.append('-');
        }

        if ((flag & S_IXOTH) != 0) {
            buf.append('x');
        } else {
            buf.append('-');
        }
        return (buf.toString());
    }
}


