package io.github.huiyu.ssh4j.file;

import jodd.util.StringUtil;
import io.github.huiyu.ssh4j.util.PathUtil;

import java.util.Date;

public class SshFile {

    private String name;
    private String path;
    private long length;
    private FileType type;
    private FilePermission permission;
    private String owner;
    private String group;
    private Date lastAccessTime;
    private Date lastModifiedTime;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getLength() {
        return length;
    }

    public void setLength(long length) {
        this.length = length;
    }

    public FileType getType() {
        return type;
    }

    public void setType(FileType type) {
        this.type = type;
    }

    public FilePermission getPermission() {
        return permission;
    }

    public void setPermission(FilePermission permission) {
        this.permission = permission;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public Date getLastAccessTime() {
        return lastAccessTime;
    }

    public void setLastAccessTime(Date lastAccessTime) {
        this.lastAccessTime = lastAccessTime;
    }

    public Date getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(Date lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public boolean isDirectory() {
        return type.equals(FileType.DIRECTORY);
    }

    public boolean isFile() {
        return !isDirectory();
    }

    public String getParent() {
        if (StringUtil.isBlank(path)) {
            return null;
        } else {
            return PathUtil.getParentPath(path);
        }
    }
}
