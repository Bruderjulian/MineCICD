package com.lemonlightmc.minecicd.pending;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * On-disk store for control-API requests. An accepted request is written here before
 * any action runs; the runner advances a pointer as actions complete. Non-terminated
 * requests are resumed after the server is fully loaded on boot. Retries by requestId
 * are idempotent (no double-run).
 */
public class PendingStore {

    private final Path dir;

    public PendingStore(Path dataFolder) {
        this.dir = dataFolder.resolve("pending");
    }

    public Path dir() {
        return dir;
    }

    public void save(PendingRequest request) {
        try {
            Files.createDirectories(dir);
            JSONObject json = request.toJson();
            Files.write(dir.resolve(request.requestId() + ".json"), json.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist pending request " + request.requestId(), e);
        }
    }

    public void delete(String requestId) {
        try {
            Files.deleteIfExists(dir.resolve(requestId + ".json"));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to delete pending request " + requestId, e);
        }
    }

    public Optional<PendingRequest> load(String requestId) {
        try {
            Path file = dir.resolve(requestId + ".json");
            if (!java.nio.file.Files.isRegularFile(file)) {
                return Optional.empty();
            }
            JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            return Optional.of(PendingRequest.fromJson(json));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public List<PendingRequest> loadAll() {
        List<PendingRequest> out = new ArrayList<>();
        try {
            if (!Files.isDirectory(dir)) {
                return out;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
                    try {
                        JSONObject json = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
                        out.add(PendingRequest.fromJson(json));
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }
}