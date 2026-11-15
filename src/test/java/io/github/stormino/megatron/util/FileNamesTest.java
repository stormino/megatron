package io.github.stormino.megatron.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileNamesTest {

    @Test
    void sanitizes() {
        assertEquals("a_b_c.txt", FileNames.sanitizeFileName("a/b\\c.txt", "x"));
        assertEquals("x", FileNames.sanitizeFileName("..", "x"));
        assertEquals("x", FileNames.sanitizeFileName("  ", "x"));
        assertEquals("_CON.txt", FileNames.sanitizeFileName("CON.txt", "x"));
        assertEquals("name", FileNames.sanitizeFileName("name. . ", "x"));
        assertEquals(".._etc_passwd", FileNames.sanitizeFileName("../etc/passwd", "x"));
    }

    @Test
    void truncatesKeepingExtension() {
        String longName = "a".repeat(400) + ".mkv";
        String s = FileNames.sanitizeFileName(longName, "x");
        assertEquals(FileNames.MAX_FILE_NAME_LENGTH, s.length());
        assertTrue(s.endsWith(".mkv"));
    }
}
