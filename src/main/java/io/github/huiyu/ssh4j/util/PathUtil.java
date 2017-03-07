package io.github.huiyu.ssh4j.util;

import com.google.common.base.Splitter;

import java.util.List;


/**
 * Linux Path Util
 *
 * @author Jeffrey Yu
 */
public class PathUtil {

    public static final String SLASH = "/";

    public static String createPath(String... paths) {
        if (paths == null || paths.length == 0) {
            throw new IllegalArgumentException("At least pass one argument.");
        }

        if (paths.length == 1 && paths[0].trim().equals(SLASH)) {
            return SLASH;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < paths.length; i++) {
            String path = paths[i];
            if (path.endsWith(SLASH)) {
                path = path.substring(0, path.length() - 1);
            }
            if (i > 0 && !path.startsWith(SLASH)) {
                path = SLASH + path;
            }
            builder.append(path);
        }

        return builder.toString();
    }

    public static String getParentPath(String path) {
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

    public static String getFileName(String path) {
        if (path == null || path.trim().length() == 0) {
            throw new NullPointerException("Path can't be null or empty.");
        }

        if (SLASH.equals(path)) {
            return SLASH;
        }

        List<String> token = Splitter.on(SLASH).omitEmptyStrings().trimResults().splitToList(path);
        return token.get(token.size() - 1);

    }

    public static boolean isAbsolutePath(String path) {
        if (path == null || path.trim().length() == 0) {
            throw new NullPointerException("Path can't be null or empty.");
        }
        return path.startsWith(SLASH);
    }

    public static boolean isRelativePath(String path) {
        return !isAbsolutePath(path);
    }
}
