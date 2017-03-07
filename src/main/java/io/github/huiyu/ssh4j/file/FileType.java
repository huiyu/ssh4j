package io.github.huiyu.ssh4j.file;


import io.github.huiyu.ssh4j.exception.SshException;

/**
 * Linux File Type
 *
 * @author Jeffrey Yu
 */
public enum FileType {

    REGULAR(FileType.S_IFREG),
    DIRECTORY(FileType.S_IFDIR),
    BLOCK(FileType.S_IFBLK),
    CHARACTER(FileType.S_IFCHR),
    PIPE(FileType.S_IFIFO),
    SYMBOLIC_LINK(FileType.S_IFLNK),
    SOCKET(FileType.S_IFSOCK);

    public static final int S_IFMT = 0xf000;
    public static final int S_IFIFO = 0x1000;
    public static final int S_IFCHR = 0x2000;
    public static final int S_IFDIR = 0x4000;
    public static final int S_IFBLK = 0x6000;
    public static final int S_IFREG = 0x8000;
    public static final int S_IFLNK = 0xa000;
    public static final int S_IFSOCK = 0xc000;

    private static final FileType[] vals = FileType.values();
    private int mask;

    FileType(int mask) {
        this.mask = mask;
    }

    public static FileType parse(int mask) {
        for (FileType t : vals) {
            if (t.mask == mask) {
                return t;
            }
        }
        throw new SshException("Unknown file type: " + mask);
    }
}
