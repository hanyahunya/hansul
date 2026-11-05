package com.hansul.recipe.video;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A loopback HTTP server that records every request and plays scripted replies per method and path. */
final class StubHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Reply>> replies = new ConcurrentHashMap<>();

    private StubHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool(runnable -> {
            var thread = new Thread(runnable, "stub-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    static StubHttpServer start() {
        try {
            return new StubHttpServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Replies are used in order and the last one repeats; unscripted paths answer 404. */
    StubHttpServer on(String method, String path, Reply... scripted) {
        replies.put(method + " " + path, new ArrayDeque<>(List.of(scripted)));
        return this;
    }

    List<Recorded> requests() {
        return List.copyOf(requests);
    }

    List<Recorded> requests(String method, String path) {
        return requests.stream()
                .filter(request -> request.method().equals(method) && request.path().equals(path))
                .toList();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var body = exchange.getRequestBody().readAllBytes();
            var headers = new Headers();
            headers.putAll(exchange.getRequestHeaders());
            var uri = exchange.getRequestURI();
            requests.add(new Recorded(exchange.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(), headers, body));

            var queue = replies.get(exchange.getRequestMethod() + " " + uri.getRawPath());
            Reply reply;
            synchronized (this) {
                reply = queue == null ? Reply.status(404) : queue.size() > 1 ? queue.poll() : queue.peek();
            }
            if (reply.delay() != null) {
                Thread.sleep(reply.delay());
            }
            if (reply.status() < 0) {
                return;
            }
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            // Closing before any response was sent drops the connection, which the client sees as an IOException.
            exchange.close();
        }
    }

    record Recorded(String method, String path, String query, Headers headers, byte[] body) {
        String header(String name) {
            return headers.getFirst(name);
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    record Reply(int status, Map<String, String> headers, String body, Duration delay) {
        static Reply json(int status, String body) {
            return new Reply(status, Map.of("Content-Type", "application/json"), body, null);
        }

        static Reply status(int status) {
            return new Reply(status, Map.of(), "", null);
        }

        static Reply dropConnection() {
            return new Reply(-1, Map.of(), "", null);
        }

        Reply withHeader(String name, String value) {
            var merged = new LinkedHashMap<>(headers);
            merged.put(name, value);
            return new Reply(status, merged, body, delay);
        }

        Reply delayedBy(Duration duration) {
            return new Reply(status, headers, body, duration);
        }
    }
}
