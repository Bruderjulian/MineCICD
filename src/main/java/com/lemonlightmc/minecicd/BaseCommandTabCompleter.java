package com.lemonlightmc.minecicd;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;

public class BaseCommandTabCompleter implements TabCompleter {
    @Override
    public ArrayList<String> onTabComplete(final CommandSender sender, final Command command, final String label,
            final String[] args) {
        final ArrayList<String> unfiltered = getUnfiltered(sender, args);
        if (unfiltered == null) {
            return null;
        }

        final String filter = args[args.length - 1];
        final String argInputReplaced = filter.replace("\\", "/");
        final ArrayList<String> filtered = new java.util.ArrayList<>();

        for (final String argToFilter : unfiltered) {
            final String argToFilterReplaced = argToFilter.replace("\\", "/");
            if (argToFilterReplaced.startsWith(argInputReplaced)) {
                filtered.add(argToFilter);
            }
        }
        return filtered;
    }

    public @Nullable ArrayList<String> getUnfiltered(final CommandSender sender, final String[] args) {
        final int argLength = args.length;
        if (argLength == 1) {
            final ArrayList<String> list = new java.util.ArrayList<>();
            list.add("add");
            list.add("remove");
            list.add("pull");
            list.add("push");
            list.add("reset");
            list.add("revert");
            list.add("rollback");
            list.add("log");
            list.add("reload");
            list.add("diff");
            list.add("status");
            list.add("help");
            list.add("script");
            list.add("resolve");
            list.removeIf(s -> !sender.hasPermission("minecicd." + s));
            return list;
        }

        final String subCommand = args[0];
        if (!sender.hasPermission("minecicd." + subCommand)) {
            return new java.util.ArrayList<>();
        }

        if (args.length != 2) {
            return new ArrayList<>();
        }

        switch (subCommand) {
            case "add": {
                final String filter = args[1];
                final String[] children = filter.split("[/\\\\]");

                final File root = new File(new File(".").getAbsolutePath());
                File current = root;
                for (int i = 0; i < children.length - 1; i++) {
                    final String s = children[i];
                    current = new File(current, s);
                    if (!current.exists()) {
                        return new ArrayList<>();
                    }
                }

                // if it ends with "/" or "\", set current to the last child
                if ((filter.endsWith("/") || filter.endsWith("\\")) && children.length > 0) {
                    current = new File(current, children[children.length - 1]);
                }

                final File[] list = current.listFiles();
                if (list == null) {
                    return new ArrayList<>();
                }

                final ArrayList<String> returnable = new ArrayList<>();
                for (final File file : list) {
                    if (file.getName().equals(".git"))
                        continue;

                    String relativePath = root.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath())
                            .toString();

                    if (file.isDirectory()) {
                        relativePath += File.separator;
                    }

                    returnable.add(relativePath);
                }
                return returnable;
            }
            case "remove": {
                final String filter = args[1];
                final String[] children = filter.split("[/\\\\]");

                final File root = new File(".");
                File current = root;
                for (int i = 0; i < children.length - 1; i++) {
                    final String s = children[i];
                    current = new File(current, s);
                    if (!current.exists()) {
                        return new ArrayList<>();
                    }
                }

                // if it ends with "/" or "\", set current to the last child
                if (filter.endsWith("/") || filter.endsWith("\\")) {
                    current = new File(current, children[children.length - 1]);
                }

                final File[] list = current.listFiles();
                if (list == null) {
                    return new ArrayList<>();
                }

                final ArrayList<String> returnable = new ArrayList<>();
                for (final File file : list) {
                    if (file.getName().equals(".git"))
                        continue;

                    String relativePath = root.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath())
                            .toString();

                    if (file.isDirectory()) {
                        relativePath += File.separator;
                    }

                    returnable.add(relativePath);
                }
                return returnable;
            }
            case "diff": {
                final ArrayList<String> list = new ArrayList<>();
                list.add("local");
                list.add("remote");
                return list;
            }
            case "resolve": {
                final ArrayList<String> list = new ArrayList<>();
                list.add("merge-abort");
                list.add("repo-reset");
                list.add("reset-local-changes");
                return list;
            }
            case "script": {
                if (!Config.getBoolean("webhooks.allow-scripts")) {
                    return new ArrayList<>();
                }

                final File scriptsFolder = new File(MineCICD.instance().getDataFolder().getAbsolutePath() + "/scripts");
                if (!scriptsFolder.exists()) {
                    return new ArrayList<>();
                }

                final File[] list = scriptsFolder.listFiles();
                if (list == null) {
                    return new ArrayList<>();
                }

                final ArrayList<String> returnable = new ArrayList<>();
                for (final File file : list) {
                    if (file.getName().endsWith(".sh")) {
                        returnable.add(file.getName());
                    }
                }
                return returnable;
            }
            case "log": {
                try (Git git = Git.open(new File("."))) {
                    final ArrayList<String> list = new ArrayList<>();

                    final Iterable<RevCommit> commits = git.log().call();
                    for (final RevCommit commit : commits) {
                        list.add(commit.getName());
                    }

                    final int pages = (int) Math.ceil((double) list.size() / 10);
                    for (int i = 1; i <= pages; i++) {
                        list.add(String.valueOf(i));
                    }

                    return list;
                } catch (final Exception e) {
                    return new ArrayList<>();
                }
            }
            default:
                return new ArrayList<>();
        }
    }
}
