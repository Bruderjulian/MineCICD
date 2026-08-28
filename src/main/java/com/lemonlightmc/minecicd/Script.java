package com.lemonlightmc.minecicd;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;

import static com.lemonlightmc.minecicd.Messages.getMessage;
import static com.lemonlightmc.minecicd.MineCICD.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.logging.Level;

public abstract class Script {
    public static void loadDefaultScript() {
        final File scriptsDir = new File(plugin.getDataFolder(), "scripts");
        final File exampleScriptFile = new File(scriptsDir, "example_script.sh");
        if (exampleScriptFile.exists())
            return;
        exampleScriptFile.getParentFile().mkdirs();

        final InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(MineCICD.plugin.getResource("example_script.sh")), StandardCharsets.UTF_8);

        final Scanner scanner = new Scanner(reader);
        try {
            Files.write(exampleScriptFile.toPath(), scanner.useDelimiter("\\A").next().getBytes());
        } catch (final IOException e) {
            MineCICD.log("Failed to write example_script.txt", Level.SEVERE);
            MineCICD.logError(e);
        }
    }

    public static void run(final String script) throws Exception {
        final boolean ownsBusy = !MineCICD.busyLock;
        MineCICD.busyLock = true;

        final String bar = MineCICD.addBar(Messages.getCleanMessage("bossbar-script", true), BarColor.BLUE, BarStyle.SOLID);
        try {
            final File scriptsFolder = new File(plugin.getDataFolder(), "scripts");
            final File scriptFile = new File(scriptsFolder, script + ".sh");

            final List<String> lines = Files.readAllLines(scriptFile.toPath().toAbsolutePath());

            final int[] result = { -1 };
            final String[] output = { "" };
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    for (int i = 0; i < lines.size(); i++) {
                        final String line = lines.get(i);
                        if (line.startsWith("#")) {
                            continue;
                        }

                        if (line.startsWith("! ")) {
                            try {
                                final ProcessBuilder b = new ProcessBuilder(line.substring(3).split(" "));
                                b.inheritIO();
                                final Process p = b.start();
                                result[0] = p.waitFor();
                            } catch (final Exception e) {
                                final int finalI = i;
                                output[0] = getMessage(
                                        "script-error-console",
                                        true,
                                        new HashMap<String, String>() {
                                            {
                                                put("script", script);
                                                put("line", String.valueOf(finalI + 1));
                                                put("command", line);
                                                put("error", e.getMessage());
                                            }
                                        });
                                result[0] = 1;
                                break;
                            }
                        } else {
                            final int finalI = i;
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                try {
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
                                    result[0] = 0;
                                } catch (final Exception e) {
                                    output[0] = getMessage(
                                            "script-error-console",
                                            true,
                                            new HashMap<String, String>() {
                                                {
                                                    put("script", script);
                                                    put("line", String.valueOf(finalI + 1));
                                                    put("command", line);
                                                    put("error", e.getMessage());
                                                }
                                            });
                                    result[0] = 1;
                                }
                            }).getOwner();
                            if (result[0] == 1) {
                                break;
                            }
                        }

                        if (result[0] != 0) {
                            final int finalI = i;
                            output[0] = getMessage(
                                    "script-error-console",
                                    true,
                                    new HashMap<String, String>() {
                                        {
                                            put("script", script);
                                            put("line", String.valueOf(finalI + 1));
                                            put("command", line);
                                            put("error", "Exited with exit code " + result[0]);
                                        }
                                    });
                            break;
                        }
                    }
                } catch (final Exception e) {
                    MineCICD.logError(e);
                }
            });

            while (result[0] == -1) {
                Thread.sleep(100);
            }

            if (result[0] != 0) {
                throw new Exception(output[0]);
            }

            MineCICD.changeBar(bar, Messages.getCleanMessage("bossbar-script-success", true), BarColor.GREEN,
                    BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
        } catch (final Exception e) {
            MineCICD.changeBar(bar, Messages.getCleanMessage("bossbar-script-failed", true), BarColor.RED,
                    BarStyle.SEGMENTED_12);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            throw e;
        } finally {
            if (ownsBusy) {
                MineCICD.busyLock = false;
            }
        }
    }
}
