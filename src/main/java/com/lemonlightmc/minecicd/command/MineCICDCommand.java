package com.lemonlightmc.minecicd.command;

import com.lemonlightmc.minecicd.CicdService;
import com.lemonlightmc.minecicd.git.Results;
import com.lemonlightmc.minecicd.messaging.Messages;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.function.Predicate;

public class MineCICDCommand {

    private final CicdService service;
    private final Messages messages;

    public MineCICDCommand(CicdService service, Messages messages) {
        this.service = service;
        this.messages = messages;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("minecicd")
                .then(Commands.literal("pull")
                        .requires(req("minecicd.pull"))
                        .then(Commands.literal("force")
                                .executes(ctx -> {
                                    service.pull(sender(ctx), true);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            service.pull(sender(ctx), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("push")
                        .requires(req("minecicd.push"))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.push(sender(ctx), StringArgumentType.getString(ctx, "message"));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            messages.send(sender(ctx), "push-usage", Map.of("label", "minecicd"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("add")
                        .requires(req("minecicd.add"))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.add(sender(ctx), StringArgumentType.getString(ctx, "path"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("remove")
                        .requires(req("minecicd.remove"))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.remove(sender(ctx), StringArgumentType.getString(ctx, "path"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("reset")
                        .requires(req("minecicd.reset"))
                        .then(Commands.argument("commit", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.reset(sender(ctx), StringArgumentType.getString(ctx, "commit"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("revert")
                        .requires(req("minecicd.revert"))
                        .then(Commands.argument("commit", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.revert(sender(ctx), StringArgumentType.getString(ctx, "commit"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("rollback")
                        .requires(req("minecicd.rollback"))
                        .then(Commands.argument("date", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.rollback(sender(ctx), StringArgumentType.getString(ctx, "date"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("script")
                        .requires(req("minecicd.script"))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    for (String s : service.scriptNames()) {
                                        builder.suggest(s);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    service.script(sender(ctx), StringArgumentType.getString(ctx, "name"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("resolve")
                        .requires(req("minecicd.resolve"))
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("merge-abort");
                                    builder.suggest("repo-reset");
                                    builder.suggest("reset-local-changes");
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    service.resolve(sender(ctx), StringArgumentType.getString(ctx, "mode"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("log")
                        .requires(req("minecicd.log"))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> logPage(sender(ctx), IntegerArgumentType.getInteger(ctx, "page"))))
                        .then(Commands.argument("commit", StringArgumentType.greedyString())
                                .executes(ctx -> showCommit(sender(ctx), StringArgumentType.getString(ctx, "commit"))))
                        .executes(ctx -> logPage(sender(ctx), 1)))
                .then(Commands.literal("status")
                        .requires(req("minecicd.status"))
                        .executes(ctx -> {
                            printStatus(sender(ctx));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("diff")
                        .requires(req("minecicd.diff"))
                        .then(Commands.literal("local")
                                .executes(ctx -> {
                                    printDiff(sender(ctx), false);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(Commands.literal("remote")
                                .executes(ctx -> {
                                    printDiff(sender(ctx), true);
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("reload")
                        .requires(req("minecicd.reload"))
                        .executes(ctx -> {
                            service.reload();
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("help")
                        .requires(req("minecicd.help"))
                        .executes(ctx -> {
                            messages.sendList(sender(ctx), "help", Map.of("label", "minecicd"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .executes(ctx -> {
                    messages.sendList(sender(ctx), "help", Map.of("label", "minecicd"));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private static CommandSender sender(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    private Predicate<CommandSourceStack> req(String permission) {
        return source -> {
            CommandSender sender = source.getSender();
            return sender != null && (sender.hasPermission(permission) || sender.hasPermission("minecicd.*"));
        };
    }

    private int logPage(CommandSender sender, int page) {
        service.log(sender, page).thenAccept(p -> {
            if (p == null || p.entries().isEmpty()) {
                return;
            }
            messages.sendRaw(sender, messages.get("log-list-header", Map.of(
                    "page", String.valueOf(p.page()),
                    "maxPage", String.valueOf(p.maxPage()))));
            for (Results.LogEntry entry : p.entries()) {
                messages.sendRaw(sender, messages.get("log-list-line", Map.of(
                        "date", entry.date(), "revision", entry.revision(),
                        "author", entry.author(), "message", Messages.escape(entry.message()))));
            }
            messages.sendRaw(sender, messages.get("log-list-end"));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int showCommit(CommandSender sender, String ref) {
        service.commit(sender, ref).thenAccept(entry -> {
            if (entry == null) {
                messages.send(sender, "log-invalid-commit");
                return;
            }
            for (var line : messages.getList("log-single-commit", Map.of(
                    "revision", entry.revision(), "author", entry.author(),
                    "date", entry.date(), "message", Messages.escape(entry.message()),
                    "changes", String.join(", ", entry.changes())))) {
                messages.sendRaw(sender, line);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int printStatus(CommandSender sender) {
        service.status(sender).thenAccept(s -> {
            if (s == null) {
                return;
            }
            for (var line : messages.getList("status", Map.of(
                    "branch", s.branch(), "remote", s.remote(),
                    "control-status", String.valueOf(service.controlActive()),
                    "control-address", service.controlAddress(),
                    "local-changes", String.valueOf(s.localChanges()),
                    "remote-changes", String.valueOf(s.remoteChanges())))) {
                messages.sendRaw(sender, line);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int printDiff(CommandSender sender, boolean remote) {
        service.diff(sender, remote).thenAccept(changes -> {
            messages.sendRaw(sender, messages.get(remote ? "diff-remote-header" : "diff-local-header"));
            if (changes == null || changes.isEmpty()) {
                messages.sendRaw(sender, messages.get("diff-no-changes"));
            } else {
                for (String change : changes) {
                    messages.sendRaw(sender, messages.get("diff-line", Map.of("change", Messages.escape(change))));
                }
            }
            messages.sendRaw(sender, messages.get("diff-end"));
        });
        return Command.SINGLE_SUCCESS;
    }
}