package com.lemonlightmc.minecicd.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the active SSE (server-sent events) connections per requestId. The control
 * runner writes progress lines to every attached client so the GitHub Action's
 * stream prints to its log, then polls the terminal status via {@link ControlStatus}.
 */
public class ProgressStream {

    private final List<HttpExchange> exchanges = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public List<HttpExchange> exchanges() {
        return exchanges;
    }

    public void add(HttpExchange exchange) {
        if (!closed.get()) {
            exchanges.add(exchange);
        }
    }

    public synchronized void broadcast(String data) {
        byte[] payload = ("data: " + data.replace("\n", "\\n") + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (HttpExchange exchange : exchanges) {
            try {
                OutputStream out = exchange.getResponseBody();
                synchronized (out) {
                    out.write(payload);
                    out.flush();
                }
            } catch (IOException ignored) {
                exchanges.remove(exchange);
            }
        }
    }

    public synchronized void close() {
        closed.set(true);
        for (HttpExchange exchange : exchanges) {
            try {
                exchange.close();
            } catch (Exception ignored) {
            }
        }
        exchanges.clear();
    }

    public boolean isClosed() {
        return closed.get();
    }
}