package io.github.huiyu.ssh4j;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import io.github.huiyu.ssh4j.execute.ExecuteResult;
import io.github.huiyu.ssh4j.file.FilePermission;
import io.github.huiyu.ssh4j.file.FileType;
import io.github.huiyu.ssh4j.file.SshFile;
import jodd.io.StreamUtil;
import jodd.util.StringUtil;
import io.github.huiyu.ssh4j.exception.SshException;
import io.github.huiyu.ssh4j.util.PathUtil;

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

import static com.google.common.base.Preconditions.*;

/**
 * Ssh Client
 *
 * @author Jeffrey Yu
 */
public class SshClient implements Closeable {

    private static final String MSG_FILE_NOT_FOUND = "File not found: ";
    private static final String MSG_FILE_ALREADY_EXISTS = "File already exists: ";
    private static final String MSG_NOT_A_FILE = "Not a file: ";
    private static final String MSG_NOT_A_DIRECTORY = "Not a directory: ";

    private static final int DEFAULT_SSH_PORT = 22;

    private static final String CHANNEL_SFTP = "sftp";
    private static final String CHANNEL_EXEC = "exec";

    private String username;
    private String hostname;
    private int port;
    private AuthType authType;
    private String identify;

    private Session session;
    private ChannelSftp sftp;

    private Map<String, String> configs = new HashMap<>();

    public SshClient(String username, String hostname, int port) {
        this.username = username;
        this.hostname = hostname;
        this.port = port;
    }

    public SshClient(String username, String hostname) {
        this(username, hostname, DEFAULT_SSH_PORT);
    }

    public static void main(String[] args) {
        SshClient client = null;
        try {
            client = new SshClient("root", "10.211.55.10");
            client.authenticateWithPassword("adminpass");
            client.open();
            System.out.println(client.execute("ls -al").stdout);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            client.close();
        }
    }

    public void authenticateWithPassword(String password) {
        if (StringUtil.isBlank(password)) {
            throw new IllegalArgumentException("Password path can't be null or empty.");
        }
        this.authType = AuthType.PASSWORD;
        this.identify = password;
    }

    public void authenticateWithPublicKey(String keyPath) {
        if (StringUtil.isBlank(keyPath)) {
            throw new IllegalArgumentException("Public key path can't be null or empty.");
        }
        this.authType = AuthType.PUBLIC_KEY;
        this.identify = keyPath;
    }

    public void setConfig(String key, String value) {
        configs.put(key, value);
        if (session != null) {
            session.setConfig(key, value);
        }
    }

    public ExecuteResult execute(String command) {
        return this.execute(command, null);
    }

    public ExecuteResult execute(String command, Map<String, String> environments) {
        List<String> commands = new ArrayList<>(1);
        commands.add(command);
        return this.execute(commands, environments);
    }


    public ExecuteResult execute(List<String> commands) {
        return this.execute(commands);
    }

    public ExecuteResult execute(List<String> commands, Map<String, String> environments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = this.execute(commands, stdout, stderr, environments);
        return new ExecuteResult(stdout.toString(), stderr.toString(), exitCode);
    }


    public int execute(String command, OutputStream stdout, OutputStream stderr) {
        return this.execute(command, stdout, stderr);
    }

    public int execute(String command, OutputStream stdout, OutputStream stderr, Map<String, String> environments) {
        List<String> commands = new ArrayList<>(1);
        commands.add(command);
        return this.execute(commands, stdout, stderr, environments);
    }

    public int execute(List<String> commands, OutputStream stdout, OutputStream stderr) {
        return this.execute(commands, stdout, stderr);
    }

    public int execute(List<String> commands, OutputStream stdout, OutputStream stderr,
                       Map<String, String> environments) {
        try {
            ChannelExec ch = (ChannelExec) session.openChannel(CHANNEL_EXEC);

            // environments
            if (environments != null && !environments.isEmpty()) {
                for (Map.Entry<String, String> environment : environments.entrySet()) {
                    ch.setEnv(environment.getKey(), environment.getValue());
                }
            }

            String comand = buildCommand(commands);
            ch.setCommand(comand);
            try {

                InputStream out = ch.getInputStream();
                InputStream err = ch.getErrStream();
                ch.connect();
                if (stdout != null) {
                    StreamUtil.copy(out, stdout);
                }
                if (stderr != null) {
                    StreamUtil.copy(err, stderr);
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

    private String buildCommand(List<String> commands) {
        return Joiner.on(" && ").skipNulls().join(commands);
    }

    public String getUsername() {
        return username;
    }

    public String getHostname() {
        return hostname;
    }

    public int getPort() {
        return port;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public Session getSession() {
        return session;
    }

    public String getConfig(String key) {
        String value = configs.get(key);
        if (StringUtil.isBlank(value) && session != null) {
            value = session.getConfig(key);
        }
        return value;
    }

    public void open() {
        checkNotNull(authType);
        setConfig("StrictHostKeyChecking", "no");
        try {
            JSch jsch = new JSch();

            if (authType == null) {
                loadAuthentication();
            }

            if (authType.equals(AuthType.PUBLIC_KEY)) {
                jsch.addIdentity(identify);
            }
            session = jsch.getSession(username, hostname, port);
            if (authType.equals(AuthType.PASSWORD)) {
                session.setPassword(this.identify);
            }

            for (Map.Entry<String, String> entry : configs.entrySet()) {
                session.setConfig(entry.getKey(), entry.getValue());
            }
            session.connect();

            // sftp channel
            sftp = (ChannelSftp) session.openChannel(CHANNEL_SFTP);
        } catch (JSchException e) {
            throw new SshException(e);
        }
    }

    public SshFile getFile(String path) {
        try {
            SftpATTRS attr = sftp.stat(path);
            // FIXME following 2 statements cost about 400ms per each.
            String groupName = getGroupNameByGID(attr.getGId());
            String userName = getUserNameByUID(attr.getUId());
            String fileName = PathUtil.getFileName(path);

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
        if (StringUtil.isNotBlank(errMsg)) {
            throw new SshException(errMsg);
        } else {
            String groupName = out.toString().trim();
            if (StringUtil.isBlank(groupName)) {
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
        if (StringUtil.isNotBlank(errMsg)) {
            throw new SshException(errMsg);
        } else {
            String userName = out.toString().trim();
            if (StringUtil.isBlank(userName)) {
                return null;
            } else {
                List<String> tokens = Splitter.on(":").omitEmptyStrings().trimResults().splitToList(userName);
                if (tokens.isEmpty()) {
                    return null;
                } else {
                    return tokens.get(0);
                }
            }
        }
    }

    public List<SshFile> listFiles(String path) {
        try {
            java.util.Vector vector = sftp.ls(path);
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
                String filePath = PathUtil.createPath(path, entry.getFilename());
                file.setPath(getAbsolutePath(filePath));
                file.setLength(attr.getSize());
                FilePermission permission = new FilePermission(attr.getPermissions());
                file.setPermission(permission);
                FileType t = FileType.parse(attr.getPermissions() & FileType.S_IFMT);
                file.setType(t);

                List<String> tokens = Splitter.on(" ").omitEmptyStrings().trimResults()
                                              .splitToList(entry.getLongname());
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

    public InputStream openFile(String path) {
        SshFile f = this.getFile(path);
        if (f == null) {
            throw new SshException(MSG_FILE_NOT_FOUND + path);
        }
        if (!f.isFile()) {
            throw new SshException(MSG_NOT_A_FILE + path);
        }
        try {
            return sftp.get(path);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    /**
     * Open an output stream.
     *
     * @param path
     * @return
     */
    public OutputStream createFile(String path) {
        if (exists(path)) {
            throw new SshException(MSG_FILE_ALREADY_EXISTS + path);
        }
        try {
            return sftp.put(path, ChannelSftp.OVERWRITE);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    public OutputStream appendFile(String path) {
        if (!exists(path)) {
            throw new SshException(MSG_FILE_NOT_FOUND + path);
        }
        try {
            return sftp.put(path, ChannelSftp.APPEND);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    public boolean exists(String path) {
        boolean exists = false;
        try {
            String p = getAbsolutePath(path);
            exists = StringUtil.isNotBlank(p);
        } catch (Exception e) {
        }
        return exists;
    }

    /**
     * Create symbolic link
     *
     * @param src
     * @param dst
     */
    public void createSymLink(String src, String dst) {
        try {
            sftp.symlink(src, dst);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    /**
     * Read symbolic link
     *
     * @param path
     * @return
     */
    public String readSymLink(String path) {
        try {
            return sftp.readlink(path);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    /**
     * Move files, correspond to `mv` command.
     *
     * @param src
     * @param dst
     */
    public void move(String src, String dst) {
        if (!exists(src)) {
            throw new SshException(MSG_FILE_NOT_FOUND + src);
        }
        if (exists(dst)) {
            throw new SshException(MSG_FILE_ALREADY_EXISTS + dst);
        }

        String command = "mv " + src + " " + dst;
        ExecuteResult result = this.execute(command);
        String errMsg = result.stderr;
        if (StringUtil.isNotBlank(errMsg)) {
            throw new SshException(errMsg);
        }
    }

    private boolean isDir(String path) {
        try {
            SftpATTRS attrs = sftp.stat(path);
            return attrs.isDir();
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    public String getAbsolutePath(String path) throws SshException {
        try {
            return sftp.realpath(path);
        } catch (SftpException e) {
            throw new SshException(e);
        }
    }

    /**
     * Smart copy local file to remote.
     *
     * If source is a directory, copy it to destination.
     * Otherwise, if destination is directory, copy source file to it.
     * Otherwise, try to copy source file to destination file.
     *
     * @param src
     * @param dst
     */
    public void copyFromLocal(String src, String dst) {

        if (StringUtil.isBlank(src)) {
            throw new IllegalArgumentException("File path can't be empty.");
        }

        if (StringUtil.isBlank(dst)) {
            dst = ".";
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
        doCopyLocalFile(src, dst);
    }

    private void doCopyLocalFile(String src, String dst) {
        File srcFile = new File(src);
        if (!srcFile.exists()) {
            throw new SshException(MSG_FILE_NOT_FOUND + src);
        }
        if (!srcFile.isFile()) {
            throw new SshException(MSG_NOT_A_FILE + src);
        }

        // mkdirs
        String parent = PathUtil.getParentPath(dst);
        if (StringUtil.isNotBlank(parent) && !exists(parent)) {
            this.mkdir(parent, true);
        }

        if (exists(dst)) {
            if (isDir(dst)) {
                throw new SshException("Remote destination '" + dst + "' is a directory");
            }
            throw new SshException(MSG_FILE_ALREADY_EXISTS + dst);
        }

        // do copy regular file
        try (FileInputStream in = new FileInputStream(srcFile);
             OutputStream out = this.createFile(dst)) {
            StreamUtil.copy(in, out);
        } catch (IOException e) {
            throw new SshException(e);
        }


        // TODO checking

//         dstFile = getFile(dst);
//         if (dstFile.getLength() != srcFile.length()) {
//         throw new IOException("Copy file failed of '" + src + "' to '" + dst + "' due to different sizes");
//         }
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
            String dstFilePath = PathUtil.createPath(dst, f.getName());
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
        String path = PathUtil.createPath(dst, srcFile.getName());
        doCopyLocalFile(src, path);
    }


    /**
     * Smart copy. If source is a directory, copy it to destination.
     * Otherwise, if destination is directory, copy source file to it.
     * Otherwise, try to copy source file to destination file.
     *
     * @param src
     * @param dst
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

        try (InputStream in = this.openFile(src);
             OutputStream out = new FileOutputStream(dstFile)) {
            StreamUtil.copy(in, out);
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
            String path = PathUtil.createPath(dst, file.getName());
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

        String dstPath = PathUtil.createPath(dstFile.getAbsolutePath(), PathUtil.getFileName(src));

        doCopyRemoteFileToLocal(src, dstPath);
    }

    /**
     * Delete file, correspond to "rm" command.
     *
     * @param path the file path
     * @param recursive remove directories and their contents recursively
     */
    public void delete(String path, boolean recursive) {
        if (!exists(path)) {
            return;
        }

        String cmd = "rm " + (recursive? "-rf " : "-f ") + path;
        ExecuteResult result = this.execute(cmd);
        String errMsg = result.stderr;
        if (StringUtil.isNotBlank(errMsg)) {
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

    /**
     * Make directories, correspond to `mkdir -p` command.
     *
     * @param path valid directory path.
     * @param recursive no error if existing, make parent directories as needed
     */
    private void mkdir(String path, boolean recursive) {
        String cmd = "mkdir " + (recursive? "-p " : " ") + path;
        ExecuteResult result = this.execute(cmd);
        String errMsg = result.stderr;
        if (StringUtil.isNotBlank(errMsg)) {
            throw new SshException(errMsg);
        }


    }

    /**
     * Change file group, correspond to `chgrp` command.
     *
     * @param path
     * @param group
     * @throws IOException
     */
    public void chgrp(String path, String group) {
        this.chgrp(path, group, false);
    }

    /**
     * Change file group, correspond to `chgrp [-R]` command.
     *
     * @param path
     * @param group
     * @param recursive operate on files and directories recursively
     */
    public void chgrp(String path, String group, boolean recursive) {
        path = getAbsolutePath(path);
        // chgrp [-opts] group file
        String cmd = "chgrp " + (recursive? " -R " : " ") + group + " " + path;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ExecuteResult result = this.execute(cmd);
        String errMsg = result.stderr;
        if (StringUtil.isNotBlank(errMsg)) {
            throw new SshException(errMsg);
        }
    }

    /**
     * Change file owner, correspond to `chown` command
     *
     * @param path
     * @param owner
     */
    public void chown(String path, String owner) {
        this.chown(path, owner, false);
    }

    /**
     * Change file owner, correspond to `chown [-R]` command
     *
     * @param path
     * @param owner
     * @param recursive operate on files and directories recursively
     */
    public void chown(String path, String owner, boolean recursive) {
        path = getAbsolutePath(path);
        // chown [-opts] user file
        String cmd = "chown " + (recursive? " -R " : " ") + owner + " " + path;
        ExecuteResult result = this.execute(cmd);
        String errMsg = result.stderr;
        if (StringUtil.isNotBlank(errMsg)) {
            throw new SshException(errMsg);
        }
    }

    public void chmod(String path, FilePermission permission) {
        // TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
        session.disconnect();
        sftp.disconnect();
    }

    public boolean isOpen() {
        return session != null && session.isConnected();
    }

    public boolean isClosed() {
        return !isOpen();
    }

    private void loadAuthentication() {
        this.authType = AuthType.PUBLIC_KEY;
        // TODO
    }

    private enum AuthType {
        PASSWORD,
        PUBLIC_KEY
    }
}
