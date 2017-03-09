package io.github.huiyu.ssh4j;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import static io.github.huiyu.ssh4j.PathUtil.createPath;

public class SshClient implements Closeable {

    private static final int KEEP_ALIVE_INTERVAL = 1000 * 5;

    private static final String MSG_FILE_NOT_FOUND = "File not found: ";
    private static final String MSG_FILE_ALREADY_EXISTS = "File already exists: ";
    private static final String MSG_NOT_A_FILE = "Not a file: ";
    private static final String MSG_NOT_A_DIRECTORY = "Not a directory: ";

    private static final String CHANNEL_SFTP = "sftp";
    private static final String CHANNEL_EXEC = "exec";

    private static final String SLASH = "/";

    private String username;
    private String host;
    private int port;
    private AuthType authType;
    private String identify;
    private boolean keepAlive;

    private volatile String homePath;

    private Session session;
    private ChannelSftp sftpChannel;

    private Map<String, String> configs;

    private List<String> sourceFiles;

    private SshClient() {
    }

    public static Builder of(String username, String hostname) {
        return new Builder(username, hostname, 22);
    }

    public static Builder of(String username, String hostname, int port) {
        return new Builder(username, hostname, port);
    }

    public ExecuteResult execute(String command) {
        return this.execute(new String[]{command});
    }

    public ExecuteResult execute(String[] commands) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = this.execute(commands, stdout, stderr);
        return new ExecuteResult(stdout.toString(), stderr.toString(), exitCode);
    }

    public int execute(String command, OutputStream stdout, OutputStream stderr) {
        String[] commands = {command};
        return this.execute(commands, stdout, stderr);
    }

    public int execute(String[] commands, OutputStream stdout, OutputStream stderr) {
        try {
            ChannelExec ch = (ChannelExec) getSession().openChannel(CHANNEL_EXEC);

            String command = buildCommand(commands);

            ch.setCommand(command);
            try {
                InputStream out = ch.getInputStream();
                InputStream err = ch.getErrStream();
                ch.connect();
                if (stdout != null) {
                    ByteStreams.copy(out, stdout);
                }
                if (stderr != null) {
                    ByteStreams.copy(err, stderr);
                }

                int exitCode;
                while ((exitCode = ch.getExitStatus()) == -1) {
                    Thread.sleep(100);
                }

                return exitCode;
            } finally {
                ch.disconnect();
            }

        } catch (Exception e) {
            throw new SshException(e);
        }
    }

    private String buildCommand(String[] commands) {
        if (commands.length == 0)
            throw new IllegalArgumentException("No available command");

        StringBuilder builder = new StringBuilder();
        for (String sourceFile : sourceFiles)
            builder.append("source ").append(sourceFile).append(" \n ");

        builder.append(commands[0]);
        for (int i = 1; i < commands.length; i++)
            builder.append(" \n ").append(commands[i]);

        return builder.toString();
    }

    public String getUsername() {
        return username;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public Map<String, String> getConfigs() {
        return configs;
    }

    public String getConfig(String key) {
        String value = configs.get(key);
        if (Strings.isNullOrEmpty(value) && session != null) {
            value = session.getConfig(key);
        }
        return value;
    }

    public synchronized void open() {
        checkNotNull(authType);
        try {
            JSch jsch = new JSch();

            if (authType.equals(AuthType.PUBLIC_KEY)) {
                jsch.addIdentity(identify);
            }
            session = jsch.getSession(username, host, port);
            if (authType.equals(AuthType.PASSWORD)) {
                session.setPassword(this.identify);
            }

            for (Map.Entry<String, String> entry : configs.entrySet()) {
                session.setConfig(entry.getKey(), entry.getValue());
            }

            if (keepAlive) {
                session.setServerAliveInterval(KEEP_ALIVE_INTERVAL);
            }

            session.connect();

        } catch (JSchException e) {
            throw new SshException(e);
        }
    }

    public SshFile getFile(String path) {

        path = getAbsolutePath(path);

        try {
            SftpATTRS attr = getSftpChannel().stat(path);

            // FIXME following 2 statements cost about 400ms per each.
            String groupName = getGroupNameByGID(attr.getGId());
            String userName = getUserNameByUID(attr.getUId());
            String fileName = getFileName(path);

            SshFile file = new SshFile();
            file.setName(fileName);
            file.setPath(path);
            file.setGroup(groupName);
            file.setOwner(userName);
            file.setLength(attr.getSize());
            FilePermission permission = new FilePermission(attr.getPermissions());
            file.setPermission(permission);
            FileType t = FileType.parse(attr.getPermissions() & FileType.S_IFMT);
            file.setType(t);

            file.setLastAccessTime(new Date(((long) attr.getATime()) * 1000L));
            file.setLastModifiedTime(new Date(((long) attr.getMTime()) * 1000L));

            return file;
        } catch (Exception e) {
            throw new SshException(e);
        }
    }

    private String getUserNameByUID(int uid) {
        String getUserCmd = "getent passwd | awk -F: '$3 == " + uid + " { print $1 }'";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        this.execute(getUserCmd, out, err);

        String errMsg = err.toString();
        if (!Strings.isNullOrEmpty(errMsg)) {
            throw new SshException(errMsg);
        } else {
            String groupName = out.toString().trim();
            if (Strings.isNullOrEmpty(groupName)) {
                return null;
            } else {
                return groupName;
            }
        }
    }

    private String getGroupNameByGID(int gid) {
        String getGroupCmd = "getent group " + gid;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        this.execute(getGroupCmd, out, err);

        String errMsg = err.toString();
        if (!Strings.isNullOrEmpty(errMsg)) {
            throw new SshException(errMsg);
        } else {
            String userName = out.toString().trim();
            if (Strings.isNullOrEmpty(userName)) {
                return null;
            } else {
                List<String> tokens = splitStringAndOmitEmpty(userName, ":");
                // Splitter.on(":").omitEmptyStrings().trimResults().splitToList(userName);
                if (tokens.isEmpty()) {
                    return null;
                } else {
                    return tokens.get(0);
                }
            }
        }
    }

    public List<SshFile> listFiles(String path) {
        path = getAbsolutePath(path);

        try {
            Vector vector = getSftpChannel().ls(path);
            if (vector == null || vector.isEmpty()) {
                return Collections.EMPTY_LIST;
            }

            List<SshFile> sshFiles = new ArrayList<>(vector.size());
            for (Iterator<LsEntry> iter = vector.iterator(); iter.hasNext(); ) {
                LsEntry entry = iter.next();

                String filename = entry.getFilename();
                if (filename.equals(".") || filename.equals("..")) {
                    continue;
                }

                SftpATTRS attr = entry.getAttrs();
                SshFile file = new SshFile();
                file.setName(entry.getFilename());
                String filePath = createPath(path, entry.getFilename());
                file.setPath(getAbsolutePath(filePath));
                file.setLength(attr.getSize());
                FilePermission permission = new FilePermission(attr.getPermissions());
                file.setPermission(permission);
                FileType t = FileType.parse(attr.getPermissions() & FileType.S_IFMT);
                file.setType(t);

                List<String> tokens = splitStringAndOmitEmpty(entry.getLongname(), " ");
                String owner = tokens.get(2);
                String group = tokens.get(3);

                file.setOwner(owner);
                file.setGroup(group);

                file.setLastAccessTime(new Date(((long) attr.getATime()) * 1000L));
                file.setLastModifiedTime(new Date(((long) attr.getMTime()) * 1000L));
                sshFiles.add(file);
            }
            return sshFiles;
        } catch (Exception e) {
            if (e instanceof SshException) {
                throw (SshException) e;
            }
            throw new SshException(e);
        }
    }

    public InputStream readFile(String path) {
        SshFile f = this.getFile(path);

        if (f == null) {
            throw new SshException(MSG_FILE_NOT_FOUND + path);
        }
        if (!f.isFile()) {
            throw new SshException(MSG_NOT_A_FILE + path);
        }

        try {
            return getSftpChannel().get(path);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    /**
     * Open an output stream.
     */
    public OutputStream createFile(String path) {
        return createFile(path, false);
    }

    public OutputStream createFile(String path, boolean overwrite) {
        if (path.startsWith("~")) {
            path = path.replace("~", ".");
        }

        if (exists(path) && !overwrite) {
            throw new SshException(MSG_FILE_ALREADY_EXISTS + path);
        }

        try {
            return getSftpChannel().put(path, ChannelSftp.OVERWRITE);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    public OutputStream appendFile(String path) {
        if (!exists(path)) {
            throw new SshException(MSG_FILE_NOT_FOUND + path);
        }

        try {
            return getSftpChannel().put(path, ChannelSftp.APPEND);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    public boolean exists(String path) {
        boolean exists = false;
        try {
            String p = getAbsolutePath(path);
            exists = !Strings.isNullOrEmpty(p);
        } catch (Exception e) {
        }
        return exists;
    }

    /**
     * Create symbolic link
     */
    public void createSymLink(String src, String dst) {
        try {
            getSftpChannel().symlink(src, dst);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    /**
     * Read symbolic link
     */
    public String readSymLink(String path) {
        ChannelSftp sftp = getSftpChannel();
        try {
            return sftp.readlink(path);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    public void move(String src, String dst) {
        if (!exists(src)) {
            throw new SshException(MSG_FILE_NOT_FOUND + src);
        }
        if (exists(dst)) {
            throw new SshException(MSG_FILE_ALREADY_EXISTS + dst);
        }

        String command = "mv " + src + " " + dst;
        ExecuteResult result = this.execute(command);
        String errMsg = result.err;
        if (!Strings.isNullOrEmpty(errMsg)) {
            throw new SshException(errMsg);
        }
    }

    private boolean isDir(String path) {
        ChannelSftp sftp = getSftpChannel();
        try {
            SftpATTRS attrs = sftp.stat(path);
            return attrs.isDir();
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    public String getHomePath() {
        if (Strings.isNullOrEmpty(homePath)) {
            synchronized (this) {
                if (Strings.isNullOrEmpty(homePath)) {
                    ExecuteResult result = execute("echo $HOME");
                    if (!result.hasError()) {
                        homePath = result.out.trim();
                    } else {
                        throw new SshException("Can't fetch environment variable: $HOME");
                    }

                }
            }
        }
        return homePath;
    }

    public String getAbsolutePath(String path) throws SshException {
        if (path.startsWith("~")) {
            path = getHomePath() + path.substring(1);
        }

        ChannelSftp sftp = getSftpChannel();
        try {
            return sftp.realpath(path);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    /**
     * Smart copy local file to remote.
     * <p>
     * If source is a directory, copy it to destination. Otherwise, if
     * destination is directory, copy source file to it. Otherwise, try to copy
     * source file to destination file.
     */
    public void copyFromLocal(String src, String dst, boolean overwrite) {
        if (Strings.isNullOrEmpty(src) || Strings.isNullOrEmpty(dst)) {
            throw new IllegalArgumentException("File path can't be empty.");
        }

        if (dst.startsWith("~")) {
            dst = getHomePath() + dst.substring(1);
        }

        File srcFile = new File(src);
        if (!srcFile.exists()) {
            throw new SshException("Local file not found: " + src);
        }

        if (srcFile.isDirectory()) {
            doCopyLocalFileToRemote(src, dst);
            return;
        }
        if (exists(dst) && isDir(dst)) {
            doCopyLocalFileToRemoteDir(src, dst);
            return;
        }
        doCopyLocalFile(src, dst, overwrite);
    }

    public void copyFromLocal(String src, String dst) {
        copyFromLocal(src, dst, false);
    }

    private void doCopyLocalFile(String src, String dst, boolean overwrite) {
        File srcFile = new File(src);
        if (!srcFile.exists()) {
            throw new SshException(MSG_FILE_NOT_FOUND + src);
        }
        if (!srcFile.isFile()) {
            throw new SshException(MSG_NOT_A_FILE + src);
        }

        // mkdirs
        String parent = getParentPath(dst);
        if (!Strings.isNullOrEmpty(parent) && !exists(parent)) {
            this.mkdir(parent, true);
        }

        if (exists(dst) && !overwrite) {
            if (isDir(dst)) {
                throw new SshException("Remote destination '" + dst + "' is a directory");
            }
            throw new SshException(MSG_FILE_ALREADY_EXISTS + dst);
        }

        // do copy regular file
        try (FileInputStream in = new FileInputStream(srcFile); OutputStream out = this.createFile(dst, overwrite)) {
            ByteStreams.copy(in, out);
        } catch (IOException e) {
            throw new SshException(e);
        }

        // TODO checking
    }

    private void doCopyLocalFile(String src, String dst) {
        doCopyLocalFile(src, dst, false);
    }

    private void doCopyLocalFileToRemote(String src, String dst) {
        File srcFile = new File(src);
        if (!srcFile.exists()) {
            throw new SshException(MSG_FILE_NOT_FOUND + src);
        }
        if (!srcFile.isDirectory()) {
            throw new SshException(MSG_NOT_A_DIRECTORY + src);
        }

        if (exists(dst) && !isDir(dst)) {
            throw new SshException(MSG_NOT_A_DIRECTORY + dst);
        }

        File[] files = srcFile.listFiles();
        if (files == null) {
            throw new SshException("Failed to list contents of: " + src);
        }

        for (File f : files) {
            String dstFilePath = createPath(dst, f.getName());
            if (f.isDirectory()) {
                doCopyLocalFileToRemote(f.getPath(), dstFilePath);
            } else {
                doCopyLocalFile(f.getPath(), dstFilePath);
            }
        }
    }

    private void doCopyLocalFileToRemoteDir(String src, String dst) {
        if (exists(dst) && !isDir(dst)) {
            throw new SshException(MSG_NOT_A_DIRECTORY + dst);
        }
        File srcFile = new File(src);
        String path = createPath(dst, srcFile.getName());
        doCopyLocalFile(src, path);
    }

    /**
     * Smart copy. If source is a directory, copy it to destination. Otherwise,
     * if destination is directory, copy source file to it. Otherwise, try to
     * copy source file to destination file.
     */
    public void copyToLocal(String src, String dst) {
        if (!exists(src)) {
            throw new SshException(MSG_FILE_NOT_FOUND + src);
        }

        if (isDir(src)) {
            doCopyRemoteDirToLocal(src, dst);
            return;
        }
        File dstFile = new File(dst);
        if (dstFile.isDirectory()) {
            doCopyRemoteFileToLocalDir(src, dst);
            return;
        }

        doCopyRemoteFileToLocal(src, dst);

    }

    private void doCopyRemoteFileToLocal(String src, String dst) {
        File dstFile = new File(dst);

        File dstParent = dstFile.getParentFile();
        if (dstParent != null && dstParent.exists() == false) {
            dstParent.mkdirs();
        }

        if (dstFile.exists()) {
            if (dstFile.isDirectory()) {
                throw new SshException("Destination '" + dst + "' is a directory.");
            } else {
                throw new SshException(MSG_FILE_ALREADY_EXISTS + dst);
            }
        }

        // do copy regular file

        try (InputStream in = this.readFile(src); OutputStream out = new FileOutputStream(dstFile)) {
            ByteStreams.copy(in, out);
        } catch (IOException e) {
            throw new SshException(e);
        }

        // TODO checking
    }

    private void doCopyRemoteDirToLocal(String src, String dst) {
        File dstDir = new File(dst);
        if (dstDir.exists() && dstDir.isDirectory() == false) {
            throw new SshException(MSG_NOT_A_DIRECTORY + dst);
        }

        List<SshFile> files = this.listFiles(src);

        for (SshFile file : files) {
            String fName = file.getName();
            if (".".equals(fName) || "..".equals(fName)) {
                continue;
            }
            String path = createPath(dst, file.getName());
            if (file.isDirectory()) {
                doCopyRemoteDirToLocal(file.getPath(), path);
            } else {
                doCopyRemoteFileToLocal(file.getPath(), path);
            }
        }
    }

    private void doCopyRemoteFileToLocalDir(String src, String dst) {
        File dstFile = new File(dst);
        if (dstFile.exists() && dstFile.isDirectory() == false) {
            throw new SshException(MSG_NOT_A_DIRECTORY + dst);
        }

        String dstPath = createPath(dstFile.getAbsolutePath(), getFileName(src));

        doCopyRemoteFileToLocal(src, dstPath);
    }

    /**
     * Delete file, correspond to "rm" command.
     *
     * @param path      the file path
     * @param recursive remove directories and their contents recursively
     */
    public void delete(String path, boolean recursive) {
        if (!exists(path)) {
            return;
        }

        String cmd = "rm " + (recursive ? "-rf " : "-f ") + path;
        ExecuteResult result = this.execute(cmd);
        String errMsg = result.err;
        if (!Strings.isNullOrEmpty(errMsg)) {
            throw new SshException(errMsg);
        }
    }

    /**
     * Delete file, correspond to "rm" command.
     *
     * @param path the file path
     */
    public void delete(String path) {
        this.delete(path, false);
    }

    /**
     * Make directories, correspond to `mkdir` command.
     *
     * @param path valid directory path.
     */
    public void mkdir(String path) {
        this.mkdir(path, false);
    }

    public void mkdirs(String path) {
        this.mkdir(path, true);
    }

    /**
     * Make directories, correspond to `mkdir -p` command.
     *
     * @param path          valid directory path.
     * @param createParents no error if existing, make parent directories as needed
     */
    private void mkdir(String path, boolean createParents) {
        String cmd = "mkdir " + (createParents ? "-p " : " ") + path;
        ExecuteResult result = this.execute(cmd);
        String errMsg = result.err;
        if (!Strings.isNullOrEmpty(errMsg)) {
            throw new SshException(errMsg);
        }

    }

    /**
     * Change file group, correspond to `chgrp` command.
     */
    public void chgrp(String path, String group) {
        this.chgrp(path, group, false);
    }

    public void chgrp(String path, String group, boolean recursive) {
        path = getAbsolutePath(path);
        // chgrp [-opts] group file
        String cmd = "chgrp " + (recursive ? " -R " : " ") + group + " " + path;
        ExecuteResult result = this.execute(cmd);
        String errMsg = result.err;
        if (!Strings.isNullOrEmpty(errMsg)) {
            throw new SshException(errMsg);
        }
    }

    public void chown(String path, String owner) {
        this.chown(path, owner, false);
    }

    public void chown(String path, String owner, boolean recursive) {
        path = getAbsolutePath(path);
        // chown [-opts] user file
        String cmd = "chown " + (recursive ? " -R " : " ") + owner + " " + path;
        ExecuteResult result = this.execute(cmd);
        String errMsg = result.err;
        if (!Strings.isNullOrEmpty(errMsg)) {
            throw new SshException(errMsg);
        }
    }

    public void chmod(String path, FilePermission permission) {
        chmod(path, permission, false);
    }

    public void chmod(String path, FilePermission permission, boolean recursive) {
        String cmd = "chmod " + (recursive ? " -R " : " ") + Integer.toOctalString(permission.flag) + " " + path;
        ExecuteResult result = this.execute(cmd);
        if (result.hasError()) {
            throw new SshException(result.err);
        }
    }

    @Override
    public void close() {
        session.disconnect();
    }

    public boolean isOpen() {
        return session != null && session.isConnected();
    }

    public boolean isClosed() {
        return !isOpen();
    }

    private synchronized Session getSession() {
        try {
            ChannelExec testChannel = (ChannelExec) session.openChannel(CHANNEL_EXEC);
            testChannel.setCommand("true");
            testChannel.connect();
            testChannel.disconnect();
        } catch (Throwable t) {
            open();
        }
        return session;
    }

    private synchronized ChannelSftp getSftpChannel() {
        if (sftpChannel == null || sftpChannel.isClosed()) {
            try {
                Session session = getSession();
                sftpChannel = (ChannelSftp) session.openChannel(CHANNEL_SFTP);
                sftpChannel.connect();
            } catch (JSchException e) {
                throw new SshException(e);
            }
        }
        return sftpChannel;
    }

    private enum AuthType {
        PASSWORD, PUBLIC_KEY
    }

    public static class Builder {

        private String username;
        private String host;
        private int port;
        private AuthType authType;
        private String identify;

        private List<String> sourceFiles = new ArrayList<>();

        private Map<String, String> configs = new HashMap<>();

        private boolean keepAlive = false;

        public Builder(String username, String host, int port) {
            this.username = username;
            this.host = host;
            this.port = port;
        }

        public Builder authenticateWithPassword(String password) {
            if (Strings.isNullOrEmpty(password)) {
                throw new IllegalArgumentException("Password can't be null or empty.");
            }
            this.authType = AuthType.PASSWORD;
            this.identify = password;
            return this;
        }

        public Builder authenticateWithKey(String privateKey) {
            if (Strings.isNullOrEmpty(privateKey)) {
                throw new IllegalArgumentException("Private key path can't be null or empty.");
            }
            this.authType = AuthType.PUBLIC_KEY;
            this.identify = privateKey;
            return this;
        }

        public Builder keepAlive() {
            this.keepAlive = true;
            return this;
        }

        /**
         * Not supported yet.
         */
        private Builder enableCompression() {
            this.setConfig("compression.s2c", "zlib@openssh.com,zlib,none");
            this.setConfig("compression.c2s", "zlib@openssh.com,zlib,none");
            this.setConfig("compression_level", "9");
            return this;
        }

        public Builder setConfig(String key, String value) {
            this.configs.put(key, value);
            return this;
        }

        public Builder addSourceFile(String file) {
            this.sourceFiles.add(file);
            return this;
        }

        public SshClient create() {
            if (null == this.authType)
                throw new SshException("No authentication information.");

            setConfig("StrictHostKeyChecking", "no");

            SshClient client = new SshClient();
            client.username = this.username;
            client.host = this.host;
            client.port = this.port;
            client.authType = this.authType;
            client.identify = this.identify;
            client.configs = this.configs;
            client.keepAlive = this.keepAlive;
            client.sourceFiles = this.sourceFiles;

            client.open();
            return client;
        }
    }

    private <T> T checkNotNull(T reference) {
        if (reference == null)
            throw new NullPointerException();
        return reference;
    }

    private <T> T checkNotNull(T reference, Object errorMessage) {
        if (reference == null) {
            throw new NullPointerException(String.valueOf(errorMessage));
        }

        return reference;
    }

    private void checkArgument(boolean expression) {
        if (!expression) {
            throw new IllegalArgumentException();
        }
    }

    private void checkArgument(boolean expression, Object errorMessage) {
        if (!expression) {
            throw new IllegalArgumentException(String.valueOf(errorMessage));
        }
    }

    private String getParentPath(String path) {
        if (path == null || path.trim().length() == 0) {
            throw new NullPointerException("Path can't be null or empty.");
        }

        if (path.endsWith(SLASH)) {
            path = path.substring(path.length() - 1);
        }

        if (path.contains(SLASH)) {
            return path.substring(0, path.lastIndexOf(SLASH));
        } else {
            return "";
        }
    }

    private String getFileName(String path) {
        if (SLASH.equals(path)) {
            return SLASH;
        }
        List<String> tokens = splitStringAndOmitEmpty(path, SLASH);
        return tokens.get(tokens.size() - 1);
    }

    private boolean isAbsolutePath(String path) {
        return path.startsWith(SLASH);
    }

    private boolean isRelativePath(String path) {
        return !isAbsolutePath(path);
    }

    private List<String> splitStringAndOmitEmpty(String s, String delimiter) {
        String[] tokens = s.split(delimiter);
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            if (!Strings.isNullOrEmpty(token)) {
                result.add(token.trim());
            }
        }

        return result;
    }
}

