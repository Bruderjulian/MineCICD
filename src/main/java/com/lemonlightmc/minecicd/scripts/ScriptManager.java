package com.lemonlightmc.minecicd.scripts;

import com.lemonlightmc.minecicd.MineCICD;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.bukkit.command.CommandSender;

public class ScriptManager {

    private final MineCICD plugin;
    private final Path scriptsDir;
    private Map<String, Script> scripts;

    public ScriptManager(MineCICD plugin) {
        this.plugin = plugin;
        this.scriptsDir = plugin.getServerRoot().resolve("plugins").resolve("MineCICD").resolve("scripts");
    }

    public void reload() {
        if (scripts == null) {
            scripts = new ConcurrentHashMap<>();
        } else {
            scripts.clear();
        }
        if (!Files.isDirectory(scriptsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(scriptsDir)) {
            stream.filter(Files::isRegularFile).sorted()
                    .forEach(p -> {
                        final String name = p.getFileName().toString();
                        scripts.put(name, new Script(scriptsDir, name));
                    });
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to load scripts: " + e.getMessage());
        }
    }

    public boolean scriptExists(String name) {
        return scripts.containsKey(name);
    }

    public Set<String> listScripts() {
        return scripts.keySet();
    }

    public Script getScript(final String name) {
        return scripts.get(name);
    }

    public void run(final String name, final CommandSender executor, Consumer<String> consoleLine) {
        Script script = scripts.get(name);
        script.run(executor, plugin, consoleLine);
    }

}