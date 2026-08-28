package com.lemonlightmc.minecicd;

import com.sun.net.httpserver.HttpServer;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MineCICD extends JavaPlugin {
    public FileConfiguration config;
    public static MineCICD instance;
    public HttpServer webServer;
    public HashMap<String, BossBar> busyBars = new HashMap<>();
    public boolean busyLock = false;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        config = getConfig();

        GitUtils.loadGitIgnore();
        Messages.loadMessages();
        Script.loadDefaultScript();

        try {
            GitSecret.configureGitSecretFiltering(GitSecret.readFromSecretsStore());
        } catch (IOException | InvalidConfigurationException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        setupWebHook();

        Objects.requireNonNull(this.getCommand("minecicd")).setExecutor(new BaseCommand());
        Objects.requireNonNull(this.getCommand("minecicd")).setTabCompleter(new BaseCommandTabCompleter());

        try (Git ignored = Git.open(new File("."))) {
        } catch (final Exception ignored) {
        }
    }

    public void setupWebHook() {
        final int port = config.getInt("webhooks.port");
        final String path = config.getString("webhooks.path");
        if (port == 0) {
            stopWebHookServer();
        }
        try {
            stopWebHookServer();

            String serverIp;
            try {
                final URL whatismyip = URI.create("https://checkip.amazonaws.com").toURL();
                final BufferedReader in = new BufferedReader(new InputStreamReader(whatismyip.openStream()));
                serverIp = in.readLine();
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }

            webServer = HttpServer.create(new InetSocketAddress(port), 0);
            webServer.createContext("/" + path, new WebhookHandler());
            webServer.setExecutor(null);
            webServer.start();

            getLogger().log(Level.INFO,
                    "MineCICD is now listening on: \"http://" + serverIp + ":" + port + "/" + path + "\"");
        } catch (final IOException e) {
            logError(e);
        }
    }

    public void reload()
            throws GitAPIException, IOException, InvalidConfigurationException, InterruptedException {
        instance.saveDefaultConfig();

        for (final String type : busyBars.keySet()) {
            removeBar(type, 0);
        }

        Config.reload();
        Messages.loadMessages();
        GitUtils.loadGitIgnore();
        Script.loadDefaultScript();
        GitUtils.setBranchIfInited();
        GitSecret.configureGitSecretFiltering(GitSecret.readFromSecretsStore());
        setupWebHook();
    }

    @Override
    public void onDisable() {
        for (final String type : busyBars.keySet()) {
            removeBar(type, 0);
        }

        if (stopWebHookServer()) {
            getLogger().log(Level.INFO, "MineCICD stopped listening.");
        }
    }

    public static MineCICD instance() {
        if (instance == null) {
            throw new IllegalStateException("Instance has not been initialized yet");
        }
        return instance;
    }

    public static Logger logger() {
        return instance().getLogger();
    }

    public boolean stopWebHookServer() {
        if (webServer != null) {
            try {
                webServer.stop(0);
            } catch (final Exception ignored) {
                return false;
            }
            webServer = null;
            return true;
        }
        return false;
    }

    public void logError(final Exception e) {
        getLogger().log(Level.SEVERE, e.getMessage(), e);
        final StringBuilder stackTrace = new StringBuilder();
        for (final StackTraceElement element : e.getStackTrace()) {
            stackTrace.append(element.toString()).append("\n");
        }
        getLogger().log(Level.SEVERE, stackTrace.toString());
    }

    public String addBar(final String title, final BarColor color, final BarStyle style) {
        if (!Config.getBoolean("bossbar.enabled"))
            return "";
        final String random = String.valueOf(System.currentTimeMillis());

        busyBars.put(random, Bukkit.createBossBar(title, color, style));

        final ArrayList<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.removeIf(player -> !player.hasPermission("minecicd.notify"));

        for (final Player player : players) {
            busyBars.get(random).addPlayer(player);
        }

        return random;
    }

    public void changeBar(final String type, final String title, final BarColor color, final BarStyle style) {
        if (!Config.getBoolean("bossbar.enabled"))
            return;
        if (!busyBars.containsKey(type))
            return;

        busyBars.get(type).setTitle(title);
        busyBars.get(type).setColor(color);
        busyBars.get(type).setStyle(style);
    }

    public void removeBar(final String type, final int delay) {
        if (!Config.getBoolean("bossbar.enabled"))
            return;
        if (!busyBars.containsKey(type))
            return;

        if (delay > 0) {
            final BossBar bar = busyBars.get(type);
            final BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
                double currentProgress = bar.getProgress();
                currentProgress -= (1.0 / (double) delay);
                if (currentProgress < 0) {
                    currentProgress = 0;
                }
                bar.setProgress(currentProgress);
            }, 1, 1);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                busyBars.get(type).removeAll();
                busyBars.remove(type);
                task.cancel();
            }, delay);
        } else {
            busyBars.get(type).removeAll();
            busyBars.remove(type);
        }
    }
}
