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
import java.util.Set;

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
        Path root = plugin.getServerRoot();
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
        Path attributes = plugin.getServerRoot().resolve(ATTR_FILE);
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

    private void writeGitConfig() {
        Path gitDir = plugin.getServerRoot().resolve(".git");
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
        return "java -jar \"" + pluginJarPath() + "\" clean \"" + mappingFile() + "\"";
    }

    public String smudgeCommand() {
        return "java -jar \"" + pluginJarPath() + "\" smudge \"" + mappingFile() + "\"";
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
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to write secrets mapping: " + e.getMessage());
        }
    }
}