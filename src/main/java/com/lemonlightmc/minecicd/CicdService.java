package com.lemonlightmc.minecicd;

import com.lemonlightmc.minecicd.bossbar.BossBars;
import com.lemonlightmc.minecicd.exceptions.ScriptException;
import com.lemonlightmc.minecicd.git.CommitActions;
import com.lemonlightmc.minecicd.git.CommitActions.Action;
import com.lemonlightmc.minecicd.git.CommitActions.ActionType;
import com.lemonlightmc.minecicd.git.GitException;
import com.lemonlightmc.minecicd.git.GitService;
import com.lemonlightmc.minecicd.git.Results;
import com.lemonlightmc.minecicd.http.ControlServer;
import com.lemonlightmc.minecicd.http.ControlSecurity;
import com.lemonlightmc.minecicd.http.ControlStatus;
import com.lemonlightmc.minecicd.http.ProgressStream;
import com.lemonlightmc.minecicd.messaging.Messages;
import com.lemonlightmc.minecicd.messaging.Msg;
import com.lemonlightmc.minecicd.pending.PendingRequest;
import com.lemonlightmc.minecicd.pending.PendingRequest.Status;
import com.lemonlightmc.minecicd.pending.PendingStore;
import com.lemonlightmc.minecicd.scripts.ScriptManager;
import com.lemonlightmc.minecicd.util.Threads;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates git operations, feedback, and the control API request queue. All
 * repo/disk/network work runs on a single worker thread; Bukkit API touches are
 * marshaled to the server main thread.
 */
public class CicdService implements ControlServer.Delegate {

    private final MineCICD plugin;
    private final MineCICDConfig config;
    private final GitService git;
    private final ScriptManager scriptManager;
    private final BossBars bossBar;
    private final Messages messages;
    private final Msg msg;
    private final PendingStore pendingStore;
    private final ControlStatus controlStatus = new ControlStatus();
    private final Map<String, ProgressStream> streams = new ConcurrentHashMap<>();
    private final ExecutorService worker;
    private volatile boolean serverStarted = false;
    private final Object resumeLock = new Object();
    private final AtomicReference<String> inFlight = new AtomicReference<>(null);

    public CicdService(MineCICD plugin, MineCICDConfig config, GitService git,
            ScriptManager scriptManager, BossBars bossBar, Messages messages,
            Msg msg, PendingStore pendingStore) {
        this.plugin = plugin;
        this.config = config;
        this.git = git;
        this.scriptManager = scriptManager;
        this.bossBar = bossBar;
        this.messages = messages;
        this.msg = msg;
        this.pendingStore = pendingStore;
        this.worker = Threads.singleDaemonWorker("minecicd-worker");
    }

    public <T> CompletableFuture<T> enqueue(java.util.function.Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, worker);
    }

    // ------------------------------------------------------------------ commands

    public CompletableFuture<Boolean> pull(CommandSender sender, boolean force) {
        return enqueue(() -> {
            try {
                bossBar.show("pulling", Map.of());
                Results.PullResult result = git.pull(force);
                processCommitActions(result.commits());
                if (result.initialized()) {
                    msg.send(sender, "pull-success");
                    bossBar.show("pulled-changes", Map.of());
                    return Boolean.TRUE;
                }
                if (result.changed()) {
                    msg.send(sender, "pull-success");
                    bossBar.show("pulled-changes", Map.of());
                } else {
                    msg.send(sender, "pull-no-changes");
                    bossBar.show("pulled-no-changes", Map.of());
                }
                return Boolean.TRUE;
            } catch (GitException.PullAborted e) {
                msg.send(sender, "pull-aborted");
                bossBar.show("pull-aborted-changes", Map.of());
                return Boolean.FALSE;
            } catch (Exception e) {
                msg.send(sender, "pull-failed", Map.of("error", safeMessage(e)));
                bossBar.show("pull-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    private void processCommitActions(List<org.eclipse.jgit.revwalk.RevCommit> commits) {
        if (commits == null || commits.isEmpty()) {
            return;
        }
        ControlSecurity policy = buildPolicy();
        for (org.eclipse.jgit.revwalk.RevCommit commit : commits) {
            for (Action action : CommitActions.parseCommitMessage(commit)) {
                if (action.type() == ActionType.PULL) {
                    continue;
                }
                // C-02: gate commit actions through same policy as HTTP control API
                try {
                    policy.validateActions(List.of(action));
                } catch (ControlSecurity.RejectException e) {
                    plugin.getLogger().warning(
                            "Skipping commit action disallowed by policy: " + action + " (" + e.getMessage() + ")");
                    continue;
                }
                plugin.getLogger().info("Running commit action: " + action);
                executeAction(action, null, null);
            }
        }
    }

    private ControlSecurity buildPolicy() {
        var control = config.control();
        var actions = control.actions();
        java.util.Map<ActionType, Boolean> flags = new java.util.HashMap<>();
        flags.put(ActionType.PULL, actions.pull());
        flags.put(ActionType.PUSH, actions.push());
        flags.put(ActionType.RESTART, actions.restart());
        flags.put(ActionType.GLOBAL_RELOAD, actions.globalReload());
        flags.put(ActionType.RELOAD_PLUGIN, actions.reloadPlugins());
        flags.put(ActionType.COMMAND, actions.commands());
        flags.put(ActionType.SCRIPT, actions.scripts());
        return new ControlSecurity(control.replayWindowSeconds(), flags, actions.commandAllow(), actions.scriptAllow());
    }

    public CompletableFuture<Boolean> push(CommandSender sender, String message) {
        return enqueue(() -> {
            try {
                bossBar.show("pushing", Map.of());
                Results.PushResult result = git.push(message);
                if (result.hadChanges()) {
                    msg.send(sender, "push-success");
                    bossBar.show("pushed", Map.of());
                } else {
                    bossBar.show("push-no-changes", Map.of());
                    msg.send(sender, "push-no-changes");
                }
                return Boolean.TRUE;
            } catch (Exception e) {
                msg.send(sender, "push-failed", Map.of("error", safeMessage(e)));
                bossBar.show("push-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> add(CommandSender sender, String path) {
        return enqueue(() -> {
            try {
                int amount = git.addToTracking(path);
                msg.send(sender, "add-success", Map.of("amount", String.valueOf(amount)));
                bossBar.show("added", Map.of("amount", String.valueOf(amount)));
                return Boolean.TRUE;
            } catch (Exception e) {
                msg.send(sender, "add-failed", Map.of("error", safeMessage(e)));
                bossBar.show("adding-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> remove(CommandSender sender, String path) {
        return enqueue(() -> {
            try {
                int amount = git.removeFromTracking(path);
                msg.send(sender, "remove-success", Map.of("amount", String.valueOf(amount)));
                bossBar.show("removed", Map.of("amount", String.valueOf(amount)));
                return Boolean.TRUE;
            } catch (Exception e) {
                msg.send(sender, "remove-failed", Map.of("error", safeMessage(e)));
                bossBar.show("removing-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> reset(CommandSender sender, String commit) {
        return enqueue(() -> {
            try {
                git.reset(commit);
                msg.send(sender, "reset-success");
                bossBar.show("reset", Map.of());
                return Boolean.TRUE;
            } catch (Exception e) {
                msg.send(sender, "reset-failed", Map.of("error", safeMessage(e)));
                bossBar.show("reset-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> revert(CommandSender sender, String commit) {
        return enqueue(() -> {
            try {
                git.revert(commit);
                msg.send(sender, "revert-success");
                bossBar.show("reverted", Map.of());
                return Boolean.TRUE;
            } catch (Exception e) {
                msg.send(sender, "revert-failed", Map.of("error", safeMessage(e)));
                bossBar.show("revert-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> rollback(CommandSender sender, String date) {
        return enqueue(() -> {
            try {
                git.rollback(date);
                msg.send(sender, "rollback-success");
                bossBar.show("reset", Map.of());
                return Boolean.TRUE;
            } catch (Exception e) {
                String message = safeMessage(e);
                msg.send(sender, "rollback-failed", Map.of("error", message));
                bossBar.show("reset-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Results.LogPage> log(CommandSender sender, int page) {
        return enqueue(() -> git.log(page));
    }

    public CompletableFuture<Results.LogEntry> commit(CommandSender sender, String ref) {
        return enqueue(() -> git.getCommit(ref));
    }

    public CompletableFuture<Results.StatusInfo> status(CommandSender sender) {
        return enqueue(() -> git.status());
    }

    public CompletableFuture<List<String>> diff(CommandSender sender, boolean remote) {
        return enqueue(() -> remote ? git.diffRemote() : git.diffLocal());
    }

    public CompletableFuture<Boolean> script(CommandSender sender, String name) {
        return enqueue(() -> {
            try {
                bossBar.show("script", Map.of());
                scriptManager.run(name, sender, line -> {
                });
                msg.send(sender, "script-success");
                bossBar.show("script-success", Map.of());
                return Boolean.TRUE;
            } catch (ScriptException e) {
                msg.send(sender, "script-failed", Map.of("error", e.getMessage()));
                bossBar.show("script-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> resolve(CommandSender sender, String mode) {
        return enqueue(() -> {
            try {
                switch (mode) {
                    case "merge-abort" -> git.resolveMergeAbort();
                    case "repo-reset" -> git.resolveRepoReset();
                    case "reset-local-changes" -> git.resolveResetLocalChanges();
                    default -> {
                        msg.send(sender, "resolve-usage");
                        return Boolean.FALSE;
                    }
                }
                msg.send(sender, "resolve-success-" + mode);
                return Boolean.TRUE;
            } catch (Exception e) {
                msg.send(sender, "resolve-failed-" + mode, Map.of("error", safeMessage(e)));
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> reload() {
        return enqueue(() -> {
            plugin.reloadPlugin();
            bossBar.show("reloaded", Map.of());
            return Boolean.TRUE;
        });
    }

    // ----------------------------------------------------------------- control API

    @Override
    public boolean tryAcquireInFlight(String requestId) {
        String cur = inFlight.get();
        if (cur == null) {
            return inFlight.compareAndSet(null, requestId);
        }
        // idempotent retry for same id while in-flight
        return cur.equals(requestId);
    }

    @Override
    public void releaseInFlight(String requestId) {
        inFlight.compareAndSet(requestId, null);
    }

    @Override
    public void acceptRequest(String requestId, List<Action> actions, String branch) {
        PendingRequest existing = pendingStore.load(requestId).orElse(null);
        if (existing != null) {
            if (existing.status() != Status.RUNNING) {
                // idempotent retry: report the stored terminal status and release inFlight
                // acquired in handlePost so a different requestId is not blocked forever
                controlStatus.update(requestId, existing.status(), existing.error(),
                        existing.index(), existing.total());
                releaseInFlight(requestId);
                return;
            }
            // H-03: existing RUNNING -> do not overwrite, resume from stored progress
            controlStatus.update(requestId, existing.status(), existing.error(),
                    existing.index(), existing.total());
            return;
        }
        PendingRequest request = new PendingRequest(requestId, actions, branch);
        pendingStore.save(request);
        controlStatus.update(requestId, Status.RUNNING, null, request.index(), request.total());
        runRequestAsync(request);
    }

    @Override
    public ProgressStream progressStream(String requestId) {
        return streams.computeIfAbsent(requestId, k -> new ProgressStream());
    }

    @Override
    public ControlStatus controlStatus() {
        return controlStatus;
    }

    @Override
    public void removeRequest(String requestId) {
        streams.remove(requestId);
        controlStatus.clear(requestId);
        releaseInFlight(requestId);
    }

    private void runRequestAsync(PendingRequest request) {
        CompletableFuture.supplyAsync(() -> {
            runActions(request);
            return null;
        }, worker);
    }

    private void runActions(PendingRequest request) {
        String requestId = request.requestId();
        ProgressStream stream = streams.computeIfAbsent(requestId, k -> new ProgressStream());
        try {
            while (request.hasRemaining()) {
                Action action = request.current();
                // M-06: escape action before broadcast to SSE
                stream.broadcast("action:" + Messages.escape(String.valueOf(action)));
                boolean ok = executeAction(action, request.branch(), requestId);
                controlStatus.bump(requestId);
                if (ok) {
                    request.advance();
                    pendingStore.save(request);
                    controlStatus.update(requestId, Status.RUNNING, null,
                            request.index(), request.total());
                } else {
                    String message = "action '" + Messages.escape(String.valueOf(action)) + "' failed";
                    request.failed(message);
                    pendingStore.save(request);
                    controlStatus.update(requestId, Status.FAILED, message,
                            request.index(), request.total());
                    stream.broadcast("failed:" + Messages.escape(message));
                    stream.close();
                    removeRequest(requestId);
                    return;
                }
            }
            request.completed();
            pendingStore.save(request);
            controlStatus.update(requestId, Status.COMPLETED, null, request.total(), request.total());
            stream.broadcast("completed");
            stream.close();
            removeRequest(requestId);
        } catch (Exception e) {
            String message = Messages.escape(safeMessage(e));
            request.failed(message);
            pendingStore.save(request);
            controlStatus.update(requestId, Status.FAILED, message,
                    request.index(), request.total());
            stream.broadcast("failed:" + message);
            stream.close();
            removeRequest(requestId);
        }
    }

    private boolean executeAction(Action action, String branch, String requestId) {
        switch (action.type()) {
            case PULL -> {
                try {
                    git.pull(false);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            case PUSH -> {
                try {
                    String message = action.argument() != null ? action.argument() : config.control().pushMessage();
                    git.push(message);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            case RESTART -> {
                scheduleRestart(requestId);
                return true;
            }
            case GLOBAL_RELOAD -> {
                reloadAllPluginsIncludingPlugin();
                return true;
            }
            case RELOAD_PLUGIN -> {
                reloadPluginByName(action.argument());
                return true;
            }
            case COMMAND -> {
                dispatchConsole(action.argument());
                return true;
            }
            case SCRIPT -> {
                try {
                    scriptManager.run(action.argument(), null, line -> {
                    });
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            default -> {
                return false;
            }
        }
    }

    private void scheduleRestart(String requestId) {
        Runnable restart = () -> {
            try {
                bossBar.show("control-trigger", Map.of());
            } finally {
                Bukkit.spigot().restart();
            }
        };
        if (Bukkit.isPrimaryThread()) {
            restart.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, restart);
        }
    }

    private void reloadAllPluginsIncludingPlugin() {
        if (Bukkit.isPrimaryThread()) {
            plugin.getServer().reload();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getServer().reload());
        }
    }

    private void reloadPluginByName(String name) {
        Runnable reload = () -> {
            org.bukkit.plugin.Plugin target = Bukkit.getPluginManager().getPlugin(name);
            if (target != null) {
                Bukkit.getPluginManager().disablePlugin(target);
                Bukkit.getPluginManager().enablePlugin(target);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            reload.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, reload);
        }
    }

    private void dispatchConsole(String command) {
        Runnable run = () -> Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), command);
        if (Bukkit.isPrimaryThread()) {
            run.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, run);
        }
    }

    // ----------------------------------------------------------------- resume

    public void onServerStarted() {
        synchronized (resumeLock) {
            if (serverStarted) {
                return;
            }
            serverStarted = true;
        }
        worker.execute(this::resumePending);
    }

    private void resumePending() {
        for (PendingRequest request : pendingStore.loadAll()) {
            if (request.status() != Status.RUNNING || !request.hasRemaining()) {
                continue;
            }
            plugin.getLogger().info("Resuming pending control request " + request.requestId());
            controlStatus.update(request.requestId(), Status.RUNNING, null,
                    request.index(), request.total());
            runRequestAsync(request);
        }
    }

    public void shutdown() {
        worker.shutdownNow();
        git.close();
    }

    public boolean repoInitialized() {
        return git.isInitialized();
    }

    public Set<String> scriptNames() {
        return scriptManager.listScripts();
    }

    public boolean controlActive() {
        return plugin.isControlActive();
    }

    public String controlAddress() {
        return plugin.getControlAddress();
    }

    private String safeMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}