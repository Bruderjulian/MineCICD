package com.lemonlightmc.minecicd.bossbar;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.messaging.Messages;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

public class BossBars {

    private final MineCICD plugin;
    private final Messages messages;
    private boolean enabled;
    private int durationTicks;
    private BossBar current;

    public BossBars(MineCICD plugin, Messages messages, boolean enabled, int durationTicks) {
        this.plugin = plugin;
        this.messages = messages;
        this.enabled = enabled;
        this.durationTicks = durationTicks;
    }

    public void reconfigure(boolean enabled, int durationTicks) {
        this.enabled = enabled;
        this.durationTicks = durationTicks;
        if (!enabled) {
            removeCurrent();
        }
    }

    public void show(String path, Map<String, String> placeholders) {
        show(messages.get("bossbar-" + path, placeholders));
    }

    public void show(Component name) {
        if (!enabled) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            showAsyncSafe(name);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> showAsyncSafe(name));
        }
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
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> removeIfCurrent(bar), durationTicks);
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

    private void removeIfCurrent(BossBar bar) {
        if (current == bar) {
            removeCurrent();
        }
    }
}