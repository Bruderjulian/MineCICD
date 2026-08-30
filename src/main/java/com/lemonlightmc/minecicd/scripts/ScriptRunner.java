package com.lemonlightmc.minecicd.scripts;

import com.lemonlightmc.minecicd.MineCICD;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ScriptRunner {

    private final MineCICD plugin;
    private final Path scriptsDir;

    public ScriptRunner(MineCICD plugin) {
        this.plugin = plugin;
        this.scriptsDir = plugin.getServerRoot().resolve("plugins").resolve("MineCICD").resolve("scripts");
    }

    public boolean scriptExists(String name) {
        return resolve(name) != null;
    }

    public List<String> listScripts() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(scriptsDir)) {
            return out;
        }
        try (var stream = Files.list(scriptsDir)) {
            stream.filter(Files::isRegularFile).sorted()
                    .forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to list scripts: " + e.getMessage());
        }
        return out;
    }

    /**
     * Runs a script by file name. Console lines are dispatched as server commands on the
     * main thread; {@code !} lines are executed in the system shell. Returns the number of
     * lines processed, throwing on the first failing console command or shell error.
     */
    public int run(String name, CommandSender executor, java.util.function.Consumer<String> consoleLine) {
        Path file = resolve(name);
        if (file == null) {
            throw new ScriptException("Script not found: " + name);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ScriptException("Unable to read script: " + e.getMessage());
        }
        int count = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                count++;
                continue;
            }
            if (line.startsWith("! ")) {
                String command = line.substring(2).trim();
                runShell(command, executor, name, i + 1);
            } else {
                String command = line.startsWith("/") ? line.substring(1) : line;
                dispatchCommand(command, executor, name, i + 1);
            }
            if (consoleLine != null) {
                consoleLine.accept(line);
            }
            count++;
        }
        return count;
    }

    private void dispatchCommand(String command, CommandSender executor, String script, int line) {
        try {
            boolean success = plugin.getServer().getScheduler()
                    .callSyncMethod(plugin, () -> plugin.getServer().dispatchCommand(
                            executor != null ? executor : Bukkit.getConsoleSender(), command))
                    .get();
            if (!success) {
                throw new ScriptException("Command failed (line " + line + "): /" + command);
            }
        } catch (ScriptException e) {
            throw e;
        } catch (Exception e) {
            throw new ScriptException("Command failed (line " + line + "): /" + command + " -> " + rootMessage(e));
        }
    }

    private void runShell(String command, CommandSender executor, String script, int line) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                pb.command("cmd", "/c", command);
            } else {
                pb.command("sh", "-c", command);
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            InputStream out = process.getInputStream();
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(out, StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = reader.readLine()) != null) {
                        plugin.getLogger().info("[script] " + l);
                    }
                } catch (IOException ignored) {
                }
            }, "minecicd-script-out").start();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new ScriptException("Shell command failed (line " + line + "): " + command + " (exit " + exit + ")");
            }
        } catch (ScriptException e) {
            throw e;
        } catch (Exception e) {
            throw new ScriptException("Shell command failed (line " + line + "): " + command + " -> " + rootMessage(e));
        }
    }

    private Path resolve(String name) {
        if (name == null || name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || "..".equals(name) || name.startsWith(".")) {
            return null;
        }
        Path file;
        if (name.contains(".")) {
            file = scriptsDir.resolve(name);
        } else {
            file = scriptsDir.resolve(name + ".sh");
        }
        if (!Files.isRegularFile(file)) {
            file = scriptsDir.resolve(name + ".sh");
            if (!Files.isRegularFile(file)) {
                return null;
            }
        }
        return file.normalize();
    }

    private String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public static class ScriptException extends RuntimeException {
        public ScriptException(String message) {
            super(message);
        }

        public ScriptException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}