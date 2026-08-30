package com.lemonlightmc.minecicd.git;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommitActions {

    private static final Pattern CICD_LINE = Pattern.compile("(?im)^\\s*CICD\\s+(.+?)\\s*$");

    public enum ActionType {
        PULL,
        PUSH,
        RESTART,
        GLOBAL_RELOAD,
        RELOAD_PLUGIN,
        COMMAND,
        SCRIPT
    }

    public record Action(ActionType type, String argument) {
        @Override
        public String toString() {
            return switch (type) {
                case PULL -> "pull";
                case PUSH -> argument == null ? "push" : "push:" + argument;
                case RESTART -> "restart";
                case GLOBAL_RELOAD -> "global-reload";
                case RELOAD_PLUGIN -> "reload:" + argument;
                case COMMAND -> "command:" + argument;
                case SCRIPT -> "script:" + argument;
            };
        }
    }

    public static class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    private CommitActions() {
    }

    public static List<Action> parseCommitMessage(RevCommit commit) {
        return parseCommitMessage(commit.getFullMessage());
    }

    public static List<Action> parseCommitMessage(String message) {
        List<Action> actions = new ArrayList<>();
        if (message == null) {
            return actions;
        }
        Matcher matcher = CICD_LINE.matcher(message);
        while (matcher.find()) {
            Action action = parseItem(matcher.group(1), false);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    public static Action parseControlItem(String raw) {
        Action action = parseItem(raw, true);
        if (action == null) {
            throw new ParseException("Unknown action: " + raw);
        }
        return action;
    }

    private static Action parseItem(String raw, boolean allowPullPush) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (allowPullPush) {
            if ("pull".equals(s)) {
                return new Action(ActionType.PULL, null);
            }
            if ("push".equals(s)) {
                return new Action(ActionType.PUSH, null);
            }
            if (s.startsWith("push:")) {
                String message = s.substring(5).trim();
                if (message.isEmpty()) {
                    throw new ParseException("push requires a message");
                }
                return new Action(ActionType.PUSH, message);
            }
        }
        if ("restart".equals(s)) {
            return new Action(ActionType.RESTART, null);
        }
        if ("global-reload".equals(s)) {
            return new Action(ActionType.GLOBAL_RELOAD, null);
        }
        if ("reload".equals(s)) {
            return new Action(ActionType.GLOBAL_RELOAD, null);
        }
        if (s.startsWith("reload:") || s.startsWith("reload ")) {
            String plugin = s.substring(7).trim();
            if (plugin.isEmpty()) {
                throw new ParseException("reload requires a plugin name");
            }
            return new Action(ActionType.RELOAD_PLUGIN, plugin);
        }
        if (s.startsWith("run ")) {
            String command = s.substring(4).trim();
            if (command.isEmpty()) {
                throw new ParseException("run requires a command");
            }
            return new Action(ActionType.COMMAND, command);
        }
        if (s.startsWith("command:") || s.startsWith("command ")) {
            String command = s.substring(8).trim();
            if (command.isEmpty()) {
                throw new ParseException("command requires a command");
            }
            return new Action(ActionType.COMMAND, command);
        }
        if (s.startsWith("script:") || s.startsWith("script ")) {
            String script = s.substring(7).trim();
            if (script.isEmpty()) {
                throw new ParseException("script requires a name");
            }
            return new Action(ActionType.SCRIPT, script);
        }
        throw new ParseException("Unknown action: " + raw);
    }
}