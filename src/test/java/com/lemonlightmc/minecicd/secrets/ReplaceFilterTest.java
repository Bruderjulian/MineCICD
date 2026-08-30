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

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void cleanReplacesValuesWithPlaceholders() {
        String input = "password: s3cr3t!\ntoken: abc:def/ghi\n";
        String cleaned = ReplaceFilter.clean(input, mapping());
        assertFalse(cleaned.contains("s3cr3t!"));
        assertFalse(cleaned.contains("abc:def/ghi"));
        assertTrue(cleaned.contains(ReplaceFilter.placeholder("plugins/example/config.yml\u0000database_password")));
        assertTrue(cleaned.contains(ReplaceFilter.placeholder("plugins/example/config.yml\u0000token")));
    }

    @Test
    void smudgeRestoresValues() {
        String input = "password: " + ReplaceFilter.placeholder("plugins/example/config.yml\u0000database_password")
                + "\ntoken: " + ReplaceFilter.placeholder("plugins/example/config.yml\u0000token") + "\n";
        String smudged = ReplaceFilter.smudge(input, mapping());
        assertTrue(smudged.contains("s3cr3t!"));
        assertTrue(smudged.contains("abc:def/ghi"));
    }

    @Test
    void cleanThenSmudgeIsIdentity() {
        String original = "password: s3cr3t!\ntoken: abc:def/ghi\nother: value\n";
        String roundTrip = ReplaceFilter.smudge(ReplaceFilter.clean(original, mapping()), mapping());
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