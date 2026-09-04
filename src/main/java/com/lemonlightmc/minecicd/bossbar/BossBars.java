package com.lemonlightmc.minecicd.bossbar;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.util.Threads;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

public class BossBars {

    private final MineCICD plugin;
    private boolean enabled;
    private int durationTicks;
    private BossBar current;

    public BossBars(MineCICD plugin) {
        this.plugin = plugin;
        this.enabled = plugin.config().bossBar().enabled();
        this.durationTicks = plugin.config().bossBar().durationTicks();
    }

    public void reload() {
        this.enabled = plugin.config().bossBar().enabled();
        this.durationTicks = plugin.config().bossBar().durationTicks();
        if (!enabled) {
            removeCurrent();
        }
    }

    public void show(String path, Map<String, String> placeholders) {
        show(plugin.messages().get("bossbar-" + path, placeholders));
    }

    public void show(Component name) {
        if (!enabled) {
            return;
        }
        Threads.marshaled(plugin, () -> showAsyncSafe(name));
    }

    private void showAsyncSafe(Component name) {
        removeCurrent();
        BossBar bar = BossBar.bossBar(name, 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("minecicd.notify")) {
                player.showBossBar(bar);
            }
        }
        current = bar;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (current == bar) {
                removeCurrent();
            }
        }, durationTicks);
    }

    private void removeCurrent() {
        if (current == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("minecicd.notify")) {
                player.hideBossBar(current);
            }
        }
        current = null;
    }
}