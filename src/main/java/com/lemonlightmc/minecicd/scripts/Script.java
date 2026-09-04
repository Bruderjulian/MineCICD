package com.lemonlightmc.minecicd.scripts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.exceptions.ScriptException;

public class Script {

  private final String name;
  private final Path path;
  private String[] lines = null;

  private int timesRan = 0;
  private long lastRunTimestamp;
  private long lastRunDuration;
  private long avgDuration;

  public Script(Path dir, final String name) {
    this.name = name;
    this.path = resolvePath(dir, name);
    if (this.path == null) {
      throw new ScriptException("Script not found: " + name);
    }
  }

  public String name() {
    return name;
  }

  public Path path() {
    return path;
  }

  public int timesRan() {
    return timesRan;
  }

  public long lastRunTimestamp() {
    return lastRunTimestamp;
  }

  public long lastRunDuration() {
    return lastRunDuration;
  }

  public long avgDuration() {
    return avgDuration;
  }

  public void compile() {
    List<String> lines;
    try {
      lines = Files.readAllLines(this.path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ScriptException("Unable to read script: " + e.getMessage());
    }
    lines.stream().filter(line -> line == null || line.isBlank() || line.startsWith("#")).map(line -> line.trim());
    this.lines = lines.toArray(String[]::new);
  }

  /**
   * Runs a script by file name. Console lines are dispatched as server commands
   * on the
   * main thread; {@code !} lines are executed in the system shell. Returns the
   * number of
   * lines processed, throwing on the first failing console command or shell
   * error.
   */
  public void run(CommandSender executor, MineCICD plugin, Consumer<String> consoleLine) {
    final long start = System.currentTimeMillis();

    for (int i = 0; i < this.lines.length; i++) {
      String line = lines[i];
      plugin.getLogger().info("[script]" + name + " - Executing (line " + i + "): " + line);
      if (line.startsWith("! ")) {
        String command = line.substring(2).trim();
        runShell(command, executor, plugin, i + 1);
      } else {
        String command = line.startsWith("/") ? line.substring(1) : line;
        dispatchCommand(command, executor, plugin, i + 1);
      }
      if (consoleLine != null) {
        consoleLine.accept(line);
      }
    }

    recordRun(start);
  }

  private void dispatchCommand(String command, CommandSender executor, MineCICD plugin, int line) {
    try {
      boolean success = plugin.getServer().getScheduler()
          .callSyncMethod(plugin, () -> plugin.getServer().dispatchCommand(
              executor != null ? executor : Bukkit.getConsoleSender(), command))
          .get();
      if (!success) {
        throw new ScriptException(name + " - Command failed (line " + line + "): /" + command);
      }
    } catch (ScriptException e) {
      throw e;
    } catch (Exception e) {
      throw new ScriptException(name + " - Command failed (line " + line + "): /" + command + " -> " + rootMessage(e));
    }
  }

  private void runShell(String command, CommandSender executor, MineCICD plugin, int line) {
    try {
      ProcessBuilder pb = new ProcessBuilder();
      String os = System.getProperty("os.name", "").toLowerCase();
      if (os.contains("win")) {
        pb.command("cmd", "/c", command);
      } else {
        pb.command("sh", "-c", command);
      }
      pb.redirectErrorStream(true);
      // M-04: sandbox - restrict working dir, sanitize env
      pb.directory(plugin.getServerRoot().toFile());
      // clear and allowlist minimal env
      java.util.Map<String, String> env = pb.environment();
      String path = env.get("PATH");
      String home = env.get("HOME");
      env.clear();
      if (path != null)
        env.put("PATH", path);
      if (home != null)
        env.put("HOME", home);
      Process process = pb.start();
      InputStream out = process.getInputStream();
      Thread drain = new Thread(() -> {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(out, StandardCharsets.UTF_8))) {
          String l;
          while ((l = reader.readLine()) != null) {
            plugin.getLogger().info("[script] " + l);
          }
        } catch (IOException ignored) {
        }
      }, "minecicd-script-out");
      drain.setDaemon(true);
      drain.start();
      // M-04: per-script timeout 30s
      boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new ScriptException(name + " - Shell command timed out (line " + line + "): " + command);
      }
      int exit = process.exitValue();
      if (exit != 0) {
        throw new ScriptException(
            name + " - Shell command failed (line " + line + "): " + command + " (exit " + exit + ")");
      }
    } catch (ScriptException e) {
      throw e;
    } catch (Exception e) {
      throw new ScriptException(
          name + " - Shell command failed (line " + line + "): " + command + " -> " + rootMessage(e));
    }
  }

  private void recordRun(final long start) {
    this.timesRan++;
    this.lastRunTimestamp = System.currentTimeMillis();
    this.lastRunDuration = lastRunTimestamp - start;
    this.avgDuration = (avgDuration + lastRunDuration) / 2;
  }

  private static String rootMessage(Throwable t) {
    Throwable current = t;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  private static Path resolvePath(Path dir, String name) {
    if (name == null || name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || "..".equals(name)
        || name.startsWith(".")) {
      return null;
    }
    Path file;
    if (name.contains(".")) {
      file = dir.resolve(name);
    } else {
      file = dir.resolve(name + ".sh");
    }
    if (!Files.isRegularFile(file)) {
      file = dir.resolve(name + ".sh");
      if (!Files.isRegularFile(file)) {
        return null;
      }
    }
    // M-03: canonical-path confinement - follow symlinks in all parent components
    Path normalized = file.normalize().toAbsolutePath();
    Path base = dir.normalize().toAbsolutePath();
    if (!normalized.startsWith(base)) {
      return null;
    }
    try {
      Path realBase = base.toRealPath();
      Path realFile = normalized.toRealPath();
      if (!realFile.startsWith(realBase)) {
        return null;
      }
    } catch (IOException ignored) {
      // base or file does not exist yet - fall back to normalized check above
    }
    return normalized;
  }
}
