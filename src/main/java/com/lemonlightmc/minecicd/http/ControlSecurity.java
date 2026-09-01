package com.lemonlightmc.minecicd.http;

import com.lemonlightmc.minecicd.git.CommitActions;
import com.lemonlightmc.minecicd.git.CommitActions.Action;
import com.lemonlightmc.minecicd.git.CommitActions.ActionType;
import com.lemonlightmc.minecicd.util.Ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HMAC-SHA256 verification and authorization. Pure logic (no Bukkit dependency) so it
 * can be unit tested. Canonical signed bytes: {@code timestamp|nonce|requestId|body}.
 */
public class ControlSecurity {

    // M-01: time-windowed nonce cache to bound memory and allow expiry
    private final ConcurrentHashMap<String, Long> seenNonces = new ConcurrentHashMap<>();

    private final long replayWindowSeconds;
    private static final int MAX_NONCES = 10_000;
    private final Map<ActionType, Boolean> enabled;
    private final Set<String> allowedCommands;
    private final Set<String> allowedScripts;
    private final boolean allowPullPush;

    public static class RejectException extends RuntimeException {
        public RejectException(String message) {
            super(message);
        }
    }

    public ControlSecurity(long replayWindowSeconds,
                           Map<ActionType, Boolean> enabled,
                           List<String> allowedCommands,
                           List<String> allowedScripts) {
        this.replayWindowSeconds = replayWindowSeconds;
        this.enabled = new HashMap<>(enabled);
        this.allowedCommands = new HashSet<>(allowedCommands);
        this.allowedScripts = new HashSet<>(allowedScripts);
        this.allowPullPush = true;
    }

    public boolean verify() {
        return enabled != null && enabled.containsKey(ActionType.PULL);
    }

    /**
     * Verifies the HMAC in constant time. Throws {@link RejectException} on failure.
     */
    public void authenticate(String secret, String timestampHeader, String nonceHeader,
                             String requestIdHeader, String providedMac, byte[] body) {
        if (secret == null || secret.isEmpty()) {
            throw new RejectException("Control API is not configured");
        }
        if (providedMac == null || providedMac.isEmpty()) {
            throw new RejectException("Missing X-MineCICD-Signature");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (Exception e) {
            throw new RejectException("Invalid timestamp");
        }
        long now = System.currentTimeMillis() / 1000L;
        // L-02: distinguish future vs past instead of Math.abs
        if (timestamp > now + replayWindowSeconds) {
            throw new RejectException("Timestamp too far in future");
        }
        if (now - timestamp > replayWindowSeconds) {
            throw new RejectException("Timestamp outside replay window");
        }
        if (nonceHeader == null || nonceHeader.isEmpty()) {
            throw new RejectException("Missing nonce");
        }
        if (nonceHeader.length() > 256) {
            throw new RejectException("Nonce too large");
        }
        // M-01: prune expired nonces
        pruneNonces(now);
        if (seenNonces.size() >= MAX_NONCES) {
            throw new RejectException("Nonce cache full");
        }
        // L-01: hash body to make canonical non-ambiguous on '|' in body
        String bodyHash = sha256Hex(body);
        String canonical = timestamp + "|" + nonceHeader + "|" + requestIdHeader + "|" + bodyHash;
        byte[] expected = hmac(secret, canonical.getBytes(StandardCharsets.UTF_8));
        byte[] actual = hexDecode(providedMac);
        if (actual == null || actual.length != expected.length) {
            throw new RejectException("Invalid signature");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new RejectException("Invalid signature");
        }
        // Replay guard: fail if nonce already seen within window
        Long prev = seenNonces.putIfAbsent(nonceHeader, now);
        if (prev != null) {
            throw new RejectException("Replayed nonce");
        }
    }

    public static byte[] hmac(String secret, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexDecode(String hex) {
        if (hex.length() % 2 != 0) {
            return null;
        }
        try {
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Checks that every action is enabled (per-action flag) and, for commands/scripts,
     * allowed by its exact-name allowlist. Also validates script names against a safe pattern.
     */
    public void validateActions(List<Action> actions) {
        for (Action action : actions) {
            ActionType type = action.type();
            Boolean flag = enabled.get(type);
            if (flag == null || !flag) {
                throw new RejectException("Action not enabled: " + action);
            }
            switch (type) {
                case COMMAND -> {
                    String name = firstToken(action.argument());
                    if (name == null || !allowedCommands.contains(name)) {
                        throw new RejectException("Command not allowed: " + action.argument());
                    }
                }
                case SCRIPT -> {
                    String name = action.argument();
                    if (name == null || !isValidScriptName(name)) {
                        throw new RejectException("Script not allowed: " + name);
                    }
                }
                default -> {
                }
            }
        }
    }

    public boolean isValidRequestId(String requestId) {
        return Ids.isValidRequestId(requestId);
    }

    public static boolean isAllowedScriptName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) {
            return false;
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..") || name.startsWith(".")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    /**
     * A script is allowed only if it is on the exact-name allowlist AND its name matches
     * the safe pattern (no path traversal / separators).
     */
    private boolean isValidScriptName(String name) {
        return isAllowedScriptName(name) && allowedScripts.contains(name);
    }

    private static String firstToken(String command) {
        if (command == null) {
            return null;
        }
        String trimmed = command.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private void pruneNonces(long nowSeconds) {
        seenNonces.entrySet().removeIf(e -> nowSeconds - e.getValue() > replayWindowSeconds + 60);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data == null ? new byte[0] : data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}