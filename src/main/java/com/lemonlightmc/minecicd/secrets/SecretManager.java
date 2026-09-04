package com.lemonlightmc.minecicd.secrets;

import com.lemonlightmc.minecicd.MineCICD;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class SecretManager {

    private static final String FILTER_NAME = "minecicd";
    private static final String ATTR_FILE = ".gitattributes";

    private final MineCICD plugin;
    private final Path dataDir;
    private final Map<byte[], byte[]> mapping = new LinkedHashMap<>();

    public SecretManager(MineCICD plugin) {
        this.plugin = plugin;
        this.dataDir = plugin.getDataFolder().toPath();
    }

    public void load() {
        mapping.clear();
        Path root = plugin.serverRoot();
        File secretsFile = root.resolve("secrets.yml").toFile();
        if (secretsFile.isFile()) {
            YamlConfiguration secrets = YamlConfiguration.loadConfiguration(secretsFile);
            for (String key : secrets.getKeys(false)) {
                if (!secrets.isConfigurationSection(key)) {
                    continue;
                }
                ConfigurationSection section = secrets.getConfigurationSection(key);
                String file = section.getString("file");
                if (file == null || file.isBlank()) {
                    continue;
                }
                for (String secretKey : section.getKeys(false)) {
                    if ("file".equals(secretKey)) {
                        continue;
                    }
                    String secretValue = section.getString(secretKey);
                    if (secretValue == null || secretValue.isEmpty()) {
                        continue;
                    }
                    byte[] mapKey = (file + "\u0000" + secretKey).getBytes(StandardCharsets.UTF_8);
                    mapping.put(mapKey, secretValue.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        writeMappingFile();
        configurePointers();
    }

    public boolean hasSecrets() {
        return !mapping.isEmpty();
    }

    public List<String> files() {
        List<String> out = new ArrayList<>();
        for (byte[] keyBytes : mapping.keySet()) {
            String key = new String(keyBytes, StandardCharsets.UTF_8);
            int nul = key.indexOf('\u0000');
            String file = nul >= 0 ? key.substring(0, nul) : key;
            if (!out.contains(file)) {
                out.add(file);
            }
        }
        return out;
    }

    public Map<byte[], byte[]> mapping() {
        return mapping;
    }

    /**
     * Writes .gitattributes routing the configured files through the filter and,
     * when a .git directory exists, registers the clean/smudge commands in .git/config.
     * Both entries are written by the same shared method so that JGit operations (which
     * use the working tree) and any external git CLI usage agree on the byte stream.
     */
    private void configurePointers() {
        writeAttributes();
        writeGitConfig();
    }

    private void writeAttributes() {
        Path attributes = plugin.serverRoot().resolve(ATTR_FILE);
        try {
            StringBuilder out = new StringBuilder();
            if (Files.exists(attributes)) {
                String existing = Files.readString(attributes, StandardCharsets.UTF_8);
                int begin = existing.indexOf("# MineCICD FILTERS BEGIN");
                int end = existing.indexOf("# MineCICD FILTERS END");
                if (begin >= 0 && end > begin) {
                    out.append(existing, 0, begin);
                    out.append(existing.substring(end));
                } else {
                    out.append(existing);
                }
            }
            if (out.length() > 0 && !out.toString().endsWith(System.lineSeparator())) {
                out.append(System.lineSeparator());
            }
            out.append("# MineCICD FILTERS BEGIN").append(System.lineSeparator());
            for (String file : new LinkedHashSet<>(files())) {
                if (file != null && !file.isBlank()) {
                    out.append(escapeAttr(file)).append(" filter=").append(FILTER_NAME).append(System.lineSeparator());
                }
            }
            out.append("# MineCICD FILTERS END").append(System.lineSeparator());
            Files.write(attributes, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to write .gitattributes: " + e.getMessage());
        }
    }

    private static void hardenPermissions(Path file) {
        try {
            // H-02: set rw------- (600)
            java.util.Set<java.nio.file.attribute.PosixFilePermission> set = java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
            set.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
            set.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, set);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows fallback
            try {
                File f = file.toFile();
                f.setReadable(false, false);
                f.setWritable(false, false);
                f.setExecutable(false, false);
                f.setReadable(true, true);
                f.setWritable(true, true);
            } catch (Exception ignored2) {
            }
        }
    }

    private void writeGitConfig() {
        Path gitDir = plugin.serverRoot().resolve(".git");
        if (!Files.isDirectory(gitDir)) {
            return;
        }
        Path config = gitDir.resolve("config");
        String clean = cleanCommand();
        String smudge = smudgeCommand();
        try {
            StringBuilder out = new StringBuilder();
            if (Files.exists(config)) {
                String existing = Files.readString(config, StandardCharsets.UTF_8);
                int begin = existing.indexOf("[filter \"" + FILTER_NAME + "\"]");
                if (begin >= 0) {
                    int next = existing.indexOf("[", begin + 1);
                    out.append(existing, 0, begin);
                    if (next >= 0) {
                        out.append(existing.substring(next));
                    }
                } else {
                    out.append(existing);
                }
            }
            if (out.length() > 0 && !out.toString().endsWith(System.lineSeparator())) {
                out.append(System.lineSeparator());
            }
            out.append("[filter \"").append(FILTER_NAME).append("\"]").append(System.lineSeparator());
            out.append("\tclean = ").append(clean).append(System.lineSeparator());
            out.append("\tsmudge = ").append(smudge).append(System.lineSeparator());
            out.append("\trequired = true").append(System.lineSeparator());
            Files.write(config, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to write .git/config filters: " + e.getMessage());
        }
    }

    public String cleanCommand() {
        // %f is substituted by git with the path of the file being filtered. Passing it
        // lets ReplaceFilter scope substitution to the target file (S-07) so a secret from
        // one file is never written into another.
        return "java -jar \"" + pluginJarPath() + "\" clean \"" + mappingFile() + "\" %f";
    }

    public String smudgeCommand() {
        return "java -jar \"" + pluginJarPath() + "\" smudge \"" + mappingFile() + "\" %f";
    }

    private String pluginJarPath() {
        try {
            return new File(MineCICD.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to locate plugin jar", e);
        }
    }

    private String mappingFile() {
        return dataDir.resolve("secrets.filter").toAbsolutePath().toString();
    }

    private String escapeAttr(String file) {
        return file.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public void writeMappingFile() {
        File mappingFile = new File(mappingFile());
        try {
            Files.createDirectories(mappingFile.getParentFile().toPath());
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(mappingFile), StandardCharsets.UTF_8))) {
                for (Map.Entry<byte[], byte[]> entry : mapping.entrySet()) {
                    writer.write(Base64.getEncoder().encodeToString(entry.getKey()));
                    writer.write(':');
                    writer.write(Base64.getEncoder().encodeToString(entry.getValue()));
                    writer.newLine();
                }
            }
            hardenPermissions(mappingFile.toPath());
            // harden secrets.yml itself (600) and warn if still world-readable
            Path secretsYml = plugin.serverRoot().resolve("secrets.yml");
            if (Files.exists(secretsYml)) {
                hardenPermissions(secretsYml);
                try {
                    java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = Files.getPosixFilePermissions(secretsYml);
                    if (perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ) || perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)) {
                        plugin.getLogger().warning("secrets.yml is world-readable; run chmod 600 " + secretsYml);
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to write secrets mapping: " + e.getMessage());
        }
    }
}