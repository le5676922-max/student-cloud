package io.studentcloud.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DrivePathTest {
    @Test void rootHasStableParentAndName() {
        var root = DrivePath.of("/");
        assertTrue(root.isRoot());
        assertEquals(root, root.parent());
        assertEquals("/", root.name());
        assertEquals("", root.relative());
    }

    @Test void normalizesVirtualSeparatorsWithoutUsingHostPaths() {
        assertEquals("/课程/第一章.pdf", DrivePath.of("//课程///第一章.pdf/").value());
        assertEquals("/notes/week.txt", DrivePath.of("notes/week.txt").value());
    }

    @Test void childAndParentKeepNamesAndRootSeparate() {
        var path = DrivePath.of("/").child("课程资料").child("notes.md");
        assertEquals("notes.md", path.name());
        assertEquals("/课程资料", path.parent().value());
        assertEquals("课程资料/notes.md", path.relative());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "../secret", "/a/../b", "/./a", "/a/.", "C:/Windows",
            "/notes:secret", "\\secret", "/folder\\file", "/note\u0000.txt", "/note\n.txt",
            "/CON", "/con.txt", "/LPT1.txt", "/COM9", "/trail.", "/trail ",
            "/.studentcloud-write-private", "/.STUDENTCLOUD-copy-private"})
    void rejectsTraversalHostPathsAlternateStreamsAndPrivateNames(String path) {
        assertThrows(IllegalArgumentException.class, () -> DrivePath.of(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a/b", "..", ".", "C:", "", " ", "a\\b"})
    void childMustBeOneSafeSegment(String child) {
        assertThrows(IllegalArgumentException.class, () -> DrivePath.of("/").child(child));
    }

    @Test void rejectsOversizedSegmentsAndPaths() {
        assertThrows(IllegalArgumentException.class, () -> DrivePath.of("/" + "a".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> DrivePath.of("a/".repeat(2100)));
    }

    @Test void filenamesAreNotUrlDecodedByTheKernel() {
        assertEquals("/%2e%2e.txt", DrivePath.of("/%2e%2e.txt").value());
    }
}
