package io.github.huiyu.ssh4j.util;

import org.junit.Test;

import io.github.huiyu.ssh4j.PathUtil;

import static org.junit.Assert.*;

public class PathUtilTest {

    @Test
    public void testCreatePath() {
        String expected = "this/is/a/path";
        assertEquals(expected, PathUtil.createPath("this", "is", "a", "path"));
        assertEquals(expected, PathUtil.createPath("this", "is/", "a/", "path"));
        assertEquals(expected, PathUtil.createPath("this/", "is/", "/a/", "path"));
        assertEquals(expected, PathUtil.createPath("this/", "/is/", "/a/", "path"));

        // take care of absolute path
        assertEquals("/this/is/a/path", PathUtil.createPath("/this/", "/is/", "/a/", "path"));

        // special cases
        assertEquals("/this", PathUtil.createPath("/this"));
        assertEquals("this", PathUtil.createPath("this"));
        assertEquals("/this", PathUtil.createPath("/", "this"));
        assertEquals("/", PathUtil.createPath("/"));
    }

    @Test
    public void testGetParentPath() {
        assertEquals("this/is/a", PathUtil.getParentPath("this/is/a/path"));
        assertEquals("", PathUtil.getParentPath("path"));
    }

    @Test
    public void testGetFileName() {
        assertEquals("path", PathUtil.getFileName("this/is/a/path"));
        assertEquals("path", PathUtil.getFileName("this/is/a/path  "));
        assertEquals("path", PathUtil.getFileName("path"));
    }


    @Test
    public void testIsAbsolutePath() throws Exception {
        assertTrue(PathUtil.isAbsolutePath("/this/is/a/path"));
        assertFalse(PathUtil.isAbsolutePath("this/is/a/path"));

    }

    @Test
    public void testIsRelativePath() throws Exception {
        assertTrue(PathUtil.isRelativePath("this/is/a/path"));
        assertFalse(PathUtil.isRelativePath("/this/is/a/path"));
    }
}