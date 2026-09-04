package com.lemonlightmc.minecicd.messaging;

import com.lemonlightmc.minecicd.MineCICD;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

public class Msg {

    private final MineCICD plugin;

    public Msg(MineCICD plugin) {
        this.plugin = plugin;
    }

    public void send(CommandSender sender, Component component) {
        if (sender == null) {
            return;
        }
        marshaled(() -> sender.sendMessage(plugin.messages().prefix().append(component)));
    }

    public void sendRaw(CommandSender sender, Component component) {
        if (sender == null) {
            return;
        }
        marshaled(() -> sender.sendMessage(component));
    }

    public void send(CommandSender sender, String path) {
        send(sender, path, Map.of());
    }

    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        send(sender, plugin.messages().get(path, placeholders));
    }

    public void sendList(CommandSender sender, String path, Map<String, String> placeholders) {
        if (sender == null) {
            return;
        }
        marshaled(() -> {
            sender.sendMessage(plugin.messages().prefix());
            List<Component> lines = plugin.messages().getList(path, placeholders);
            for (Component line : lines) {
                sender.sendMessage(line);
            }
        });
    }

    private void marshaled(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }
}