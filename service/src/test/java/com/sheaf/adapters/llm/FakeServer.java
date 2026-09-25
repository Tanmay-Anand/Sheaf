package com.sheaf.adapters.llm;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A provider stand-in on 127.0.0.1 that serves recorded responses and records every request, so
 * adapters are tested without live keys or network.
 */
public final class FakeServer implements AutoCloseable {

    public record Seen(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    private record Canned(int status, String body) {}

    private final HttpServer server;
    private final Map<String, Canned> replies = new ConcurrentHashMap<>();
    public final List<Seen> seen = new ArrayList<>();

    public FakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            synchronized (seen) {
                seen.add(new Seen(exchange.getRequestMethod(), query == null ? path : path + "?" + query,
                        Map.copyOf(exchange.getRequestHeaders()), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            }
            Canned c = replies.getOrDefault(path, new Canned(404, "{\"error\":\"no fixture for " + path + "\"}"));
            byte[] out = c.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(c.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    public FakeServer on(String path, int status, String body) {
        replies.put(path, new Canned(status, body));
        return this;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public Seen last() {
        synchronized (seen) {
            return seen.get(seen.size() - 1);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
