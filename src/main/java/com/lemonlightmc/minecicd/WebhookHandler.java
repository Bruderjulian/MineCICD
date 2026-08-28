package com.lemonlightmc.minecicd;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.json.JSONObject;

import static com.lemonlightmc.minecicd.MineCICD.log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;

public class WebhookHandler implements HttpHandler {
    @Override
    public void handle(final HttpExchange t) throws IOException {
        try (final Git git = Git.open(new File("."))) {
            log("Received webhook trigger", Level.INFO);
            final StringBuilder sb = new StringBuilder();
            final InputStream ios = t.getRequestBody();
            int i;
            int max = 512000;
            while ((i = ios.read()) != -1 && max-- > 0) {
                sb.append((char) i);
            }

            if (max <= 0) {
                t.sendResponseHeaders(400, 0);
                log("Webhook failed to run due to invalid contents", Level.SEVERE);
                return;
            }

            final String response = "Response";
            t.sendResponseHeaders(200, response.length());
            final OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();

            final JSONObject json = new JSONObject(sb.toString());

            final String branch = git.getRepository().getBranch();
            if (!json.getString("ref").equals("refs/heads/" + branch)) {
                log("Webhook received for branch " + json.getString("ref") + " but expected refs/heads/" + branch,
                        Level.INFO);
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(MineCICD.plugin, () -> {
                while (MineCICD.busyLock) {
                    try {
                        Thread.sleep(100);
                    } catch (final InterruptedException e) {
                        MineCICD.logError(e);
                    }
                }

                final String bar = MineCICD.addBar(Messages.getCleanMessage("bossbar-webhook-trigger", true),
                        BarColor.BLUE, BarStyle.SOLID);
                try {
                    MineCICD.busyLock = true;

                    final String oldHead = GitUtils.getCurrentRevision();
                    final boolean updated = GitUtils.pull();
                    if (!updated) {
                        MineCICD.changeBar(bar, Messages.getCleanMessage("bossbar-webhook-no-changes", true),
                                BarColor.GREEN, BarStyle.SOLID);
                        MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
                        return;
                    }
                    final String newHead = GitUtils.getCurrentRevision();

                    final boolean allowIndividualReload = Config.getBoolean("webhooks.allow-individual-reload");
                    final boolean allowGlobalReload = Config.getBoolean("webhooks.allow-global-reload");
                    final boolean allowRestart = Config.getBoolean("webhooks.allow-restart");
                    final boolean allowScripts = Config.getBoolean("webhooks.allow-scripts");

                    final RevCommit latestCommit = git.log().setMaxCount(1).call().iterator().next();

                    final String[] lines = latestCommit.getFullMessage().split("\n");
                    final ArrayList<String> individualReload = new ArrayList<>();
                    final ArrayList<String> commands = new ArrayList<>();
                    final ArrayList<String> scripts = new ArrayList<>();
                    boolean globalReload = false;
                    boolean restart = false;
                    for (final String line : lines) {
                        if (line.startsWith("CICD")) {
                            final String command = line.substring(4).trim();
                            if (command.startsWith("reload") && allowIndividualReload) {
                                final String plugin = command.substring(7).trim();
                                individualReload.add(plugin);
                            } else if (command.equals("global-reload")) {
                                globalReload = allowGlobalReload;
                            } else if (command.equals("restart")) {
                                restart = allowRestart;
                            } else if (command.startsWith("run")) {
                                final String cmd = command.substring(4).trim();
                                commands.add(cmd);
                            } else if (command.startsWith("script")) {
                                final String script = command.substring(7).trim();
                                scripts.add(script);
                            } else {
                                log("Unknown command in CICD commit message " + command, Level.WARNING);
                            }
                        }
                    }

                    final RevCommit latest = git.log().setMaxCount(1).call().iterator().next();
                    final PersonIdent author = latest.getAuthorIdent();
                    final String name = author.getName();
                    final Date cal = author.getWhen();

                    final ObjectId oldHeadId = git.getRepository().resolve(oldHead);
                    final ObjectId newHeadId = git.getRepository().resolve(newHead);

                    final List<DiffEntry> diffs = GitUtils.getChangesBetween(git, oldHeadId, newHeadId);

                    final StringBuilder changesBuilder = new StringBuilder();
                    for (final DiffEntry diff : diffs) {
                        final DiffEntry.ChangeType type = diff.getChangeType();
                        final String path = diff.getNewPath();
                        switch (type) {
                            case ADD:
                                changesBuilder.append("&a+ ").append(path).append("\n");
                                break;
                            case DELETE:
                                changesBuilder.append("&c- ").append(path).append("\n");
                                break;
                            case MODIFY:
                                changesBuilder.append("&b# ").append(path).append("\n");
                                break;
                            case COPY:
                            case RENAME:
                                break;
                        }
                    }
                    String changes = changesBuilder.toString();
                    if (changes.isEmpty()) {
                        changes = "&7No changes";
                    } else {
                        changes = changes.substring(0, changes.length() - 1);
                    }

                    String commitMsg = latest.getFullMessage().trim();
                    if (commitMsg.endsWith("\n")) {
                        commitMsg = commitMsg.substring(0, commitMsg.length() - 1);
                    }

                    final String finalCommitMsg = commitMsg;
                    final String finalChanges = changes;
                    final String rawMsg = Messages.getMessage("webhook-event", false, new HashMap<String, String>() {
                        {
                            put("author", name);
                            put("date", new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(cal));
                            put("message", finalCommitMsg);
                            put("changes", finalChanges);
                        }
                    });

                    final BaseComponent[] components = Messages.messageToComponent(rawMsg);

                    for (final Player p : Bukkit.getOnlinePlayers()) {
                        if (p.hasPermission("minecicd.notify")) {
                            try {
                                p.sendMessage(components);
                            } catch (final Exception e) {
                                MineCICD.logError(e);
                            }
                        }
                    }

                    for (final String cmd : commands) {
                        try {
                            Bukkit.getScheduler().runTask(MineCICD.plugin,
                                    () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
                        } catch (final Exception e) {
                            MineCICD.logError(e);
                        }
                    }

                    if (allowScripts) {
                        for (final String script : scripts) {
                            try {
                                Script.run(script);
                            } catch (final Exception e) {
                                MineCICD.logError(e);
                            }
                        }
                    }

                    if (!individualReload.isEmpty()) {
                        for (final String plugin : individualReload) {
                            Plugin pl = null;

                            for (final Plugin p : Bukkit.getPluginManager().getPlugins()) {
                                if (p.getName().equalsIgnoreCase(plugin)) {
                                    pl = p;
                                    break;
                                }
                            }

                            if (pl == null) {
                                for (final Plugin p : Bukkit.getPluginManager().getPlugins()) {
                                    if (p.getName().toLowerCase().startsWith(plugin.toLowerCase())) {
                                        pl = p;
                                        break;
                                    }
                                }

                                if (pl == null) {
                                    log("Could not find plugin " + plugin + " to reload", Level.SEVERE);
                                    continue;
                                }
                            }

                            try {
                                MineCICD.plugin.getServer().getScheduler().callSyncMethod(MineCICD.plugin, () -> {
                                    String pluginStripped = plugin;
                                    // up until first "-" or " " or "." or "_"
                                    int index = pluginStripped.indexOf("-");
                                    if (index == -1)
                                        index = pluginStripped.indexOf(" ");
                                    if (index == -1)
                                        index = pluginStripped.indexOf("_");
                                    if (index == -1)
                                        index = pluginStripped.indexOf(".");
                                    if (index != -1) {
                                        pluginStripped = pluginStripped.substring(0, index);
                                    }

                                    String command = "plugman unload " + pluginStripped;
                                    try {
                                        MineCICD.plugin.getServer().dispatchCommand(
                                                MineCICD.plugin.getServer().getConsoleSender(), command);
                                    } catch (final Exception e) {
                                        MineCICD.log("Failed to unload plugin " + pluginStripped, Level.SEVERE);
                                        MineCICD.logError(e);
                                    }

                                    try {
                                        Thread.sleep(50);
                                    } catch (final InterruptedException e) {
                                        MineCICD.logError(e);
                                    }

                                    command = "plugman load " + pluginStripped;
                                    try {
                                        MineCICD.plugin.getServer().dispatchCommand(
                                                MineCICD.plugin.getServer().getConsoleSender(), command);
                                    } catch (final Exception e) {
                                        MineCICD.log("Failed to load plugin " + pluginStripped, Level.SEVERE);
                                        MineCICD.logError(e);
                                    }
                                    return null;
                                }).get();
                            } catch (final Exception e) {
                                MineCICD.logError(e);
                            }
                        }
                    }

                    if (restart) {
                        Bukkit.shutdown();
                    } else if (globalReload) {
                        Bukkit.reload();
                    }

                    MineCICD.changeBar(bar, Messages.getCleanMessage("bossbar-webhook-success", true), BarColor.GREEN,
                            BarStyle.SOLID);
                    MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
                } catch (final Exception e) {
                    MineCICD.logError(e);
                    MineCICD.changeBar(bar, Messages.getCleanMessage("bossbar-webhook-failed", true), BarColor.RED,
                            BarStyle.SEGMENTED_12);
                    MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
                } finally {
                    MineCICD.busyLock = false;
                }
            });
        }
    }
}
