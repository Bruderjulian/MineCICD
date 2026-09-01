package com.lemonlightmc.minecicd.secrets;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaceFilterTest {

    private static Map<byte[], byte[]> mapping() {
        Map<byte[], byte[]> map = new LinkedHashMap<>();
        map.put(b("plugins/example/config.yml\u0000database_password"), b("s3cr3t!"));
        map.put(b("plugins/example/config.yml\u0000token"), b("abc:def/ghi"));
        return map;
    }

    private static final String TARGET = "plugins/example/config.yml";

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void cleanReplacesValuesWithPlaceholders() {
        String input = "password: s3cr3t!\ntoken: abc:def/ghi\n";
        String cleaned = ReplaceFilter.clean(input, mapping(), TARGET);
        assertFalse(cleaned.contains("s3cr3t!"));
        assertFalse(cleaned.contains("abc:def/ghi"));
        assertTrue(cleaned.contains(ReplaceFilter.placeholder("plugins/example/config.yml\u0000database_password")));
        assertTrue(cleaned.contains(ReplaceFilter.placeholder("plugins/example/config.yml\u0000token")));
    }

    @Test
    void smudgeRestoresValues() {
        String input = "password: " + ReplaceFilter.placeholder("plugins/example/config.yml\u0000database_password")
                + "\ntoken: " + ReplaceFilter.placeholder("plugins/example/config.yml\u0000token") + "\n";
        String smudged = ReplaceFilter.smudge(input, mapping(), TARGET);
        assertTrue(smudged.contains("s3cr3t!"));
        assertTrue(smudged.contains("abc:def/ghi"));
    }

    @Test
    void cleanThenSmudgeIsIdentity() {
        String original = "password: s3cr3t!\ntoken: abc:def/ghi\nother: value\n";
        String roundTrip = ReplaceFilter.smudge(ReplaceFilter.clean(original, mapping(), TARGET), mapping(), TARGET);
        assertEquals(original, roundTrip);
    }

    @Test
    void placeholderIsStableAndUniquePerKey() {
        String a = ReplaceFilter.placeholder("keyA");
        String b = ReplaceFilter.placeholder("keyA");
        assertEquals(a, b);
        assertFalse(a.contains("keyA"), "placeholder must not leak the raw key");
    }

    @Test
    void duplicateValueAcrossTwoFilesIsScopedToTarget() {
        // Both files happen to use the same secret value; clean must only rewrite the
        // value inside the file being filtered, and smudge must only restore the matching.
        Map<byte[], byte[]> twoFiles = new LinkedHashMap<>();
        twoFiles.put(b("plugins/a/config.yml\u0000password"), b("shared-secret"));
        twoFiles.put(b("plugins/b/config.yml\u0000password"), b("shared-secret"));

        String fileA = "password: shared-secret\n";
        // Cleaning file A must produce file A's placeholder, not file B's.
        String cleanedA = ReplaceFilter.clean(fileA, twoFiles, "plugins/a/config.yml");
        assertTrue(cleanedA.contains(ReplaceFilter.placeholder("plugins/a/config.yml\u0000password")));
        assertFalse(cleanedA.contains(ReplaceFilter.placeholder("plugins/b/config.yml\u0000password")));
        assertFalse(cleanedA.contains("shared-secret"));

        String cleanedB = ReplaceFilter.clean(fileA, twoFiles, "plugins/b/config.yml");
        assertTrue(cleanedB.contains(ReplaceFilter.placeholder("plugins/b/config.yml\u0000password")));
        assertFalse(cleanedB.contains(ReplaceFilter.placeholder("plugins/a/config.yml\u0000password")));
    }

    @Test
    void smudgeNeverWritesASecretIntoTheWrongFile() {
        Map<byte[], byte[]> twoFiles = new LinkedHashMap<>();
        twoFiles.put(b("plugins/a/config.yml\u0000password"), b("secret-a"));
        twoFiles.put(b("plugins/b/config.yml\u0000password"), b("secret-b"));

        // file A contains A's placeholder; smudging A must yield only secret-a, never secret-b.
        String placeholderA = ReplaceFilter.placeholder("plugins/a/config.yml\u0000password");
        String smudgedA = ReplaceFilter.smudge(placeholderA, twoFiles, "plugins/a/config.yml");
        assertTrue(smudgedA.contains("secret-a"));
        assertFalse(smudgedA.contains("secret-b"));
        assertFalse(smudgedA.contains(placeholderA));

        // file B must only yield secret-b.
        String placeholderB = ReplaceFilter.placeholder("plugins/b/config.yml\u0000password");
        String smudgedB = ReplaceFilter.smudge(placeholderB, twoFiles, "plugins/b/config.yml");
        assertTrue(smudgedB.contains("secret-b"));
        assertFalse(smudgedB.contains("secret-a"));
    }

    @Test
    void overlappingSecretValuesAcrossTwoFilesAreIsolated() {
        // secret value "token" appears in both files; a placeholder from file A must never
        // be substituted (or smudged) while processing file B.
        Map<byte[], byte[]> twoFiles = new LinkedHashMap<>();
        twoFiles.put(b("plugins/a/config.yml\u0000token"), b("token"));
        twoFiles.put(b("plugins/b/config.yml\u0000token"), b("token"));

        // Cleaning A leaves B's placeholder untouched.
        String inputA = "value: " + ReplaceFilter.placeholder("plugins/b/config.yml\u0000token") + "\n";
        String cleanedA = ReplaceFilter.clean(inputA, twoFiles, "plugins/a/config.yml");
        assertTrue(cleanedA.contains(ReplaceFilter.placeholder("plugins/b/config.yml\u0000token")),
                "foreign placeholder must be left as-is, not resolved to a real secret");

        // Smudging A must not resolve B's placeholder.
        String smudgedA = ReplaceFilter.smudge(inputA, twoFiles, "plugins/a/config.yml");
        assertTrue(smudgedA.contains(ReplaceFilter.placeholder("plugins/b/config.yml\u0000token")));
        assertFalse(smudgedA.contains("token"), "real secret value must not leak into another file");
    }

    @Test
    void targetFileUsesForwardSlashesRegardlessOfInputSeparator() {
        Map<byte[], byte[]> map = new LinkedHashMap<>();
        map.put(b("plugins/a/config.yml\u0000password"), b("pw"));
        String cleaned = ReplaceFilter.clean("password: pw\n", map, "plugins\\a\\config.yml");
        assertTrue(cleaned.contains(ReplaceFilter.placeholder("plugins/a/config.yml\u0000password")));
    }

    @Test
    void mappingFileRoundTrip() throws Exception {
        java.io.File tmp = java.io.File.createTempFile("mapping", ".filter");
        tmp.deleteOnExit();
        try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (Map.Entry<byte[], byte[]> e : mapping().entrySet()) {
                writer.write(Base64.getEncoder().encodeToString(e.getKey()));
                writer.write(':');
                writer.write(Base64.getEncoder().encodeToString(e.getValue()));
                writer.newLine();
            }
        }
        Map<byte[], byte[]> loaded = ReplaceFilter.loadMapping(tmp);
        assertEquals(mapping().size(), loaded.size());
    }
}