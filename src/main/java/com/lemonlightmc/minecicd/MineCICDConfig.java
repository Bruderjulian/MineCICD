package com.lemonlightmc.minecicd;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed, immutable view of config.yml. The plugin loads/patches the raw YAML on
 * {@link #load()} (migrating old {@code webhooks:*} keys, backfilling missing keys,
 * and bumping supported defaults) and then exposes records to the rest of the system.
 */
public class MineCICDConfig {

    public record Git(String user, String pass, String repo, String email, String branch) {
    }

    public record BossBar(boolean enabled, int durationTicks) {
    }

    public record Tls(boolean enabled, String keystore, String password) {
    }

    public record Actions(
            boolean pull, boolean push, boolean restart, boolean globalReload,
            boolean reloadPlugins, boolean commands, List<String> commandAllow,
            boolean scripts, List<String> scriptAllow) {
    }

    public record Control(String host, int port, String path, String secret, Tls tls,
                          String pushMessage, List<String> branches, long maxBodyBytes,
                          long replayWindowSeconds, Actions actions) {
    }

    private final MineCICD plugin;
    private YamlConfiguration config;
    private Git git;
    private BossBar bossBar;
    private Control control;
    private boolean experimentalJarLoading;

    public MineCICDConfig(MineCICD plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            plugin.saveResource("config.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                patchConfig(file, defaults);
                config = YamlConfiguration.loadConfiguration(file);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to load bundled config.yml: " + e.getMessage());
        }
        try {
            config.save(file);
            hardenPermissions(file.toPath());
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to save config.yml: " + e.getMessage());
        }
        readRecords();
        warnIfWorldReadable(file.toPath());
    }

    private void patchConfig(File file, YamlConfiguration defaults) {
        boolean changed = false;
        // migrate old webhooks:* keys to control:* (if control block is absent) then drop webhooks
        if (config.contains("webhooks")) {
            ConfigurationSection webhooks = config.getConfigurationSection("webhooks");
            if (webhooks != null) {
                if (!config.contains("control.port")) {
                    config.set("control.port", webhooks.getInt("port", 0));
                }
                if (!config.contains("control.path")) {
                    config.set("control.path", webhooks.getString("path", "minecicd"));
                }
            }
            config.set("webhooks", null);
            changed = true;
        }
        for (String key : defaults.getKeys(true)) {
            if (!config.contains(key, true)) {
                config.set(key, defaults.get(key));
                changed = true;
            }
        }
        changed |= ensureActionList("control.actions.commands");
        changed |= ensureActionList("control.actions.scripts");
        if (changed) {
            try {
                config.save(file);
                hardenPermissions(file.toPath());
            } catch (Exception e) {
                plugin.getLogger().warning("Unable to save config.yml: " + e.getMessage());
            }
        }
    }

    private boolean ensureActionList(String base) {
        if (config.contains(base + ".allow")) {
            return false;
        }
        config.set(base + ".allow", new ArrayList<>(List.of()));
        return true;
    }

    private static void hardenPermissions(java.nio.file.Path file) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> set = java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
            set.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
            set.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            java.nio.file.Files.setPosixFilePermissions(file, set);
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            try {
                java.io.File f = file.toFile();
                f.setReadable(false, false);
                f.setWritable(false, false);
                f.setExecutable(false, false);
                f.setReadable(true, true);
                f.setWritable(true, true);
            } catch (Exception ignored2) {}
        }
    }

    private void warnIfWorldReadable(java.nio.file.Path file) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.nio.file.Files.getPosixFilePermissions(file);
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ) || perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)) {
                plugin.getLogger().warning(file.getFileName() + " is world-readable; run chmod 600 " + file);
            }
        } catch (Exception ignored) {}
    }

    private void readRecords() {
        ConfigurationSection g = config.getConfigurationSection("git");
        this.git = new Git(
                g.getString("user", ""),
                g.getString("pass", ""),
                g.getString("repo", ""),
                g.getString("email", "minecicd@minecicd.local"),
                g.getString("branch", "master"));

        ConfigurationSection b = config.getConfigurationSection("bossbar");
        this.bossBar = new BossBar(b.getBoolean("enabled", true), b.getInt("duration", 100));
        this.experimentalJarLoading = config.getBoolean("experimental-jar-loading", false);

        ConfigurationSection c = config.getConfigurationSection("control");
        ConfigurationSection tls = c.getConfigurationSection("tls");
        ConfigurationSection act = c.getConfigurationSection("actions");
        this.control = new Control(
                c.getString("host", "127.0.0.1"),
                c.getInt("port", 0),
                c.getString("path", "minecicd"),
                c.getString("secret", ""),
                new Tls(tls.getBoolean("enabled", false), tls.getString("keystore", ""), tls.getString("password", "")),
                c.getString("push-message", "Auto-commit by MineCICD"),
                new ArrayList<>(c.getStringList("branches")),
                c.getLong("max-body-bytes", 65536),
                c.getLong("replay-window-seconds", 300),
                new Actions(
                        act.getBoolean("pull", true),
                        act.getBoolean("push", true),
                        act.getBoolean("restart", true),
                        act.getBoolean("global-reload", false),
                        act.getBoolean("reload-plugins", true),
                        act.getBoolean("commands.enabled", false),
                        new ArrayList<>(act.getStringList("commands.allow")),
                        act.getBoolean("scripts.enabled", false),
                        new ArrayList<>(act.getStringList("scripts.allow"))));
    }

    public Git git() {
        return git;
    }

    public BossBar bossBar() {
        return bossBar;
    }

    public Control control() {
        return control;
    }

    public boolean experimentalJarLoading() {
        return experimentalJarLoading;
    }
}