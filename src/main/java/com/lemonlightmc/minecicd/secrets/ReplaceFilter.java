package com.lemonlightmc.minecicd.secrets;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure-Java clean/smudge engine for git filter-driver definitions. Invoked by the
 * plugin via git config as:
 * <pre>
 *   java -jar MineCICD.jar clean &lt;mappingFile&gt;
 *   java -jar MineCICD.jar smudge &lt;mappingFile&gt;
 * </pre>
 * The mapping file is written by {@link SecretManager} in the format
 * {@code base64(key)=base64(value)} per line, so the filter tolerates any special
 * characters and is fully cross-platform. The file's content is read on stdin and
 * written back on stdout.
 * <p>
 * Clean replaces each real secret value with a placeholder derived from its key.
 * Smudge replaces each placeholder with the real secret value. The repository only
 * ever contains placeholders; the real values live in the local mapping file only.
 */
public final class ReplaceFilter {

    private ReplaceFilter() {
    }

    public static Map<byte[], byte[]> loadMapping(File mappingFile) throws IOException {
        Map<byte[], byte[]> map = new LinkedHashMap<>();
        if (mappingFile == null || !mappingFile.isFile()) {
            return map;
        }
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                new FileInputStream(mappingFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int sep = trimmed.indexOf(':');
                if (sep <= 0) {
                    continue;
                }
                map.put(Base64.getDecoder().decode(trimmed.substring(0, sep)),
                        Base64.getDecoder().decode(trimmed.substring(sep + 1)));
            }
        }
        return map;
    }

    public static String clean(String input, Map<byte[], byte[]> mapping) {
        String current = input;
        for (Map.Entry<byte[], byte[]> entry : mapping.entrySet()) {
            String key = new String(entry.getKey(), StandardCharsets.UTF_8);
            String value = new String(entry.getValue(), StandardCharsets.UTF_8);
            String placeholder = placeholder(key);
            if (!value.isEmpty()) {
                current = current.replace(value, placeholder);
            }
        }
        return current;
    }

    public static String smudge(String input, Map<byte[], byte[]> mapping) {
        String current = input;
        for (Map.Entry<byte[], byte[]> entry : mapping.entrySet()) {
            String key = new String(entry.getKey(), StandardCharsets.UTF_8);
            String value = new String(entry.getValue(), StandardCharsets.UTF_8);
            String placeholder = placeholder(key);
            current = current.replace(placeholder, value);
        }
        return current;
    }

    public static String placeholder(String key) {
        return "__MCICD_" + Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)) + "__";
    }

    public static void main(String[] args) {
        String direction = null;
        String mappingPath = null;
        for (String arg : args) {
            if ("clean".equals(arg) || "smudge".equals(arg)) {
                direction = arg;
            } else if (mappingPath == null) {
                mappingPath = arg;
            }
        }
        if (direction == null || mappingPath == null) {
            System.err.println("Usage: java -jar MineCICD.jar <clean|smudge> <mappingFile>");
            System.exit(2);
        }
        try {
            Map<byte[], byte[]> mapping = loadMapping(new File(mappingPath));
            String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            String output = "clean".equals(direction) ? clean(input, mapping) : smudge(input, mapping);
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
                writer.write(output);
                writer.flush();
            }
        } catch (Throwable t) {
            System.err.println("MineCICD replace filter error: " + t.getMessage());
            System.exit(1);
        }
    }
}