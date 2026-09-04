package com.lemonlightmc.minecicd.http;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.git.CommitActions.Action;
import com.lemonlightmc.minecicd.http.ControlRequest.ParseException;
import com.lemonlightmc.minecicd.util.Ids;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;

public class ControlServer {

    public interface Delegate {
        void acceptRequest(String requestId, List<Action> actions, String branch);

        ProgressStream progressStream(String requestId);

        ControlStatus controlStatus();

        void removeRequest(String requestId);

        boolean tryAcquireInFlight(String requestId);

        void releaseInFlight(String requestId);
    }

    private final MineCICD plugin;
    private final String host;
    private final int port;
    private final String path;
    private final String secret;
    private final ControlSecurity security;
    private final Delegate delegate;
    private final SSLContext sslContext;
    private final long maxBodyBytes;
    // M-07/S-08: bounded, expiring per-IP failure counter for rate limiting
    private final FailureLimiter failureLimiter;
    private static final int MAX_HEADER_BYTES = 4096;
    private static final int MAX_EXCHANGES_PER_REQUEST = 4;
    private static final long SSE_IDLE_TIMEOUT_MS = 60_000L;
    private static final int MAX_FAILURE_ENTRIES = 10_000;
    private static final long FAILURE_WINDOW_MS = 10_000L;

    private HttpServer server;
    private ExecutorService httpExecutor;
    private java.util.concurrent.ScheduledExecutorService failurePurger;

    public ControlServer(MineCICD plugin, String host, int port, String path, String secret,
            ControlSecurity security, Delegate delegate, SSLContext sslContext,
            long maxBodyBytes) {
        this.plugin = plugin;
        this.host = host == null || host.isBlank() ? "0.0.0.0" : host;
        this.port = port;
        this.path = normalizePath(path);
        this.secret = secret;
        this.security = security;
        this.delegate = delegate;
        this.sslContext = sslContext;
        this.maxBodyBytes = maxBodyBytes;
        this.failureLimiter = new FailureLimiter(MAX_FAILURE_ENTRIES, FAILURE_WINDOW_MS);
    }

    private static String normalizePath(String p) {
        String s = p == null ? "minecicd" : p.trim();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public boolean start() {
        try {
            InetSocketAddress address = new InetSocketAddress(host, port);
            if (sslContext != null) {
                HttpsServer https = HttpsServer.create(address, 0);
                https.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                    @Override
                    public void configure(com.sun.net.httpserver.HttpsParameters params) {
                        try {
                            javax.net.ssl.SSLContext ctx = getSSLContext();
                            javax.net.ssl.SSLParameters sslParams = ctx.getDefaultSSLParameters();
                            // H-04: restrict to TLSv1.2/1.3
                            String[] protos = sslParams.getProtocols();
                            java.util.List<String> allowed = new java.util.ArrayList<>();
                            for (String p : protos) {
                                if ("TLSv1.2".equals(p) || "TLSv1.3".equals(p))
                                    allowed.add(p);
                            }
                            if (!allowed.isEmpty())
                                sslParams.setProtocols(allowed.toArray(new String[0]));
                            params.setSSLParameters(sslParams);
                        } catch (Exception ignored) {
                            super.configure(params);
                        }
                    }
                });
                server = https;
            } else {
                server = HttpServer.create(address, 0);
            }
            httpExecutor = java.util.concurrent.Executors.newFixedThreadPool(4,
                    com.lemonlightmc.minecicd.util.Threads.daemonFactory("minecicd-http"));
            server.setExecutor(httpExecutor);
            // S-08: periodically evict expired failure entries so distinct invalid clients
            // cannot grow the rate-limit cache without bound.
            failurePurger = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    com.lemonlightmc.minecicd.util.Threads.daemonFactory("minecicd-failure-purge"));
            failurePurger.scheduleWithFixedDelay(() -> {
                try {
                    failureLimiter.purgeExpired(System.currentTimeMillis());
                } catch (Exception e) {
                    plugin.getLogger().warning("Failure cache purge error: " + e.getMessage());
                }
            }, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
            register(server, "/" + path, this::handlePost);
            register(server, "/" + path + "/stream", this::handleStream);
            register(server, "/" + path + "/status", this::handleStatus);
            server.start();
            plugin.getLogger().info("Control API listening on " + host + ":" + port + "/" + path
                    + (sslContext != null ? " (HTTPS)" : " (HTTP)"));
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Unable to start Control API: " + e.getMessage());
            return false;
        }
    }

    private void register(HttpServer srv, String path, com.sun.net.httpserver.HttpHandler handler) {
        srv.createContext(path, handler);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        if (failurePurger != null) {
            failurePurger.shutdownNow();
            failurePurger = null;
        }
    }

    private void handlePost(HttpExchange exchange) {
        long startNano = System.nanoTime();
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            if (isRateLimited(exchange)) {
                respond(exchange, 429, "{\"error\":\"Too many requests\"}");
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
                respond(exchange, 415, "{\"error\":\"Content-Type must be application/json\"}");
                return;
            }
            // L-02/M-07: reject oversized headers before body read
            if (isHeaderTooLarge(exchange)) {
                respond(exchange, 431, "{\"error\":\"Headers too large\"}");
                return;
            }
            byte[] body = readBody(exchange);
            if (body == null) {
                respond(exchange, 413, "{\"error\":\"Request body too large\"}");
                return;
            }
            ControlRequest request;
            try {
                request = ControlRequest.parse(new String(body, StandardCharsets.UTF_8));
            } catch (ParseException e) {
                respond(exchange, 400, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
                return;
            }
            if (!Ids.isValidRequestId(request.requestId())) {
                respond(exchange, 400, "{\"error\":\"Invalid requestId\"}");
                return;
            }
            try {
                authenticate(exchange, request.requestId(), body);
            } catch (ControlSecurity.RejectException e) {
                recordFailure(exchange);
                respond(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }
            try {
                security.validateActions(request.actions());
            } catch (ControlSecurity.RejectException e) {
                respond(exchange, 403, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
                return;
            }
            String branch = request.branch();
            String configured = plugin.config() == null ? null : plugin.config().git().branch();
            if (branch == null || branch.isBlank()) {
                branch = configured;
            }
            if (!branchMatches(branch)) {
                respond(exchange, 403, "{\"error\":\"Branch not allowed\"}");
                return;
            }
            if (!delegate.tryAcquireInFlight(request.requestId())) {
                respond(exchange, 409, "{\"error\":\"Another request is already in flight\"}");
                return;
            }
            delegate.acceptRequest(request.requestId(), request.actions(), branch);
            respond(exchange, 202, "{\"accepted\":true,\"requestId\":\"" + jsonEscape(request.requestId()) + "\"}");
        } catch (Exception e) {
            plugin.getLogger().warning("Control POST error: " + e.getMessage());
            respond(exchange, 500, "{\"error\":\"Internal error\"}");
        }
        long elapsed = (System.nanoTime() - startNano) / 1_000_000;
        if (elapsed > 50) {
            plugin.getLogger().info("Control POST handled in " + elapsed + "ms");
        }
    }

    private boolean branchMatches(String requested) {
        var cfg = plugin.config();
        if (cfg == null) {
            return false;
        }
        // L-03: normalize null to configured branch, then strict allowlist
        if (requested == null) {
            requested = cfg.git().branch();
        }
        if (requested == null) {
            return false;
        }
        if (requested.equals(cfg.git().branch())) {
            return true;
        }
        return cfg.control().branches() != null && cfg.control().branches().contains(requested);
    }

    private void authenticate(HttpExchange exchange, String requestId, byte[] body) {
        var headers = exchange.getRequestHeaders();
        String ts = headers.getFirst("X-MineCICD-Timestamp");
        String nonce = headers.getFirst("X-MineCICD-Nonce");
        String mac = headers.getFirst("X-MineCICD-Signature");
        security.authenticate(secret, ts, nonce, requestId, mac, body);
    }

    private void handleStream(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        if (isRateLimited(exchange)) {
            respond(exchange, 429, "{\"error\":\"Too many requests\"}");
            return;
        }
        if (isHeaderTooLarge(exchange)) {
            respond(exchange, 431, "{\"error\":\"Headers too large\"}");
            return;
        }
        String requestId = query(exchange, "requestId");
        if (requestId == null || !Ids.isValidRequestId(requestId)) {
            respond(exchange, 400, "{\"error\":\"Missing requestId\"}");
            return;
        }
        byte[] body = readBody(exchange);
        if (body == null) {
            respond(exchange, 413, "{\"error\":\"Request body too large\"}");
            return;
        }
        try {
            authenticate(exchange, requestId, body);
        } catch (ControlSecurity.RejectException e) {
            recordFailure(exchange);
            respond(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return;
        }
        // L-04: cap exchanges per requestId
        ProgressStream existing = delegate.progressStream(requestId);
        if (existing != null && existing.exchanges().size() >= MAX_EXCHANGES_PER_REQUEST) {
            respond(exchange, 429, "{\"error\":\"Too many streams\"}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        // M-02: removed Access-Control-Allow-Origin: * (SSE is server-to-server)
        try {
            exchange.sendResponseHeaders(200, 0);
        } catch (IOException e) {
            return;
        }
        ProgressStream stream = delegate.progressStream(requestId);
        if (stream == null) {
            stream = new ProgressStream();
        }
        final ProgressStream activeStream = stream;
        if (!activeStream.add(exchange)) {
            try {
                exchange.close();
            } catch (Exception ignored) {
            }
            return;
        }
        // L-04: waiter with idle timeout and proper exchange close
        Thread waiter = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                int current = 0;
                while (!activeStream.isClosed()) {
                    if (System.currentTimeMillis() - start > SSE_IDLE_TIMEOUT_MS) {
                        activeStream.close();
                        break;
                    }
                    int count = delegate.controlStatus().eventCount(requestId);
                    if (count > current) {
                        current = count;
                        start = System.currentTimeMillis();
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {
            } finally {
                try {
                    exchange.close();
                } catch (Exception ignored) {
                }
            }
        }, "minecicd-stream-" + requestId);
        waiter.setDaemon(true);
        waiter.start();
    }

    private void handleStatus(HttpExchange exchange) {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        if (isRateLimited(exchange)) {
            respond(exchange, 429, "{\"error\":\"Too many requests\"}");
            return;
        }
        if (isHeaderTooLarge(exchange)) {
            respond(exchange, 431, "{\"error\":\"Headers too large\"}");
            return;
        }
        String requestId = query(exchange, "requestId");
        if (requestId == null || !Ids.isValidRequestId(requestId)) {
            respond(exchange, 400, "{\"error\":\"Missing requestId\"}");
            return;
        }
        byte[] body = readBody(exchange);
        if (body == null) {
            respond(exchange, 413, "{\"error\":\"Request body too large\"}");
            return;
        }
        try {
            authenticate(exchange, requestId, body);
        } catch (ControlSecurity.RejectException e) {
            recordFailure(exchange);
            respond(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        ControlStatus.Entry entry = delegate.controlStatus().get(requestId);
        if (entry == null) {
            respond(exchange, 404, "{\"error\":\"Unknown requestId\"}");
            return;
        }
        String payload = "{\"requestId\":\"" + jsonEscape(requestId) + "\",\"status\":\"" + entry.status()
                + "\",\"completed\":" + entry.completedActions() + ",\"total\":" + entry.totalActions()
                + ",\"error\":\"" + jsonEscape(entry.error() == null ? "" : entry.error()) + "\"}";
        respond(exchange, 200, payload);
    }

    private byte[] readBody(HttpExchange exchange) {
        try {
            InputStream in = exchange.getRequestBody();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBodyBytes) {
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private String query(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try {
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private void respond(HttpExchange exchange, int status, String body) {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, payload.length);
            if (payload.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            } else {
                exchange.close();
            }
        } catch (IOException e) {
            try {
                exchange.close();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isHeaderTooLarge(HttpExchange exchange) {
        long total = 0;
        for (java.util.Map.Entry<String, java.util.List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            String name = entry.getKey();
            if (name != null) {
                if (name.length() > MAX_HEADER_BYTES) {
                    return true;
                }
                total += name.length();
                if (total > MAX_HEADER_BYTES * 4) {
                    return true;
                }
            }
            for (String v : entry.getValue()) {
                if (v != null) {
                    if (v.length() > MAX_HEADER_BYTES) {
                        return true;
                    }
                    total += v.length();
                    if (total > MAX_HEADER_BYTES * 4) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isRateLimited(HttpExchange exchange) {
        String ip = clientIp(exchange);
        return failureLimiter.isRateLimited(ip, System.currentTimeMillis());
    }

    private void recordFailure(HttpExchange exchange) {
        String ip = clientIp(exchange);
        failureLimiter.recordFailure(ip, System.currentTimeMillis());
    }

    private static String clientIp(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                ? exchange.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}