package com.charodaemon.rest;

import com.charodaemon.monitor.SystemMonitor;
import com.charodaemon.monitor.model.AggregatedMetricsSnapshot;
import com.charodaemon.monitor.model.SystemMetrics;
import com.charodaemon.rest.json.GsonFactory;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class RestApiServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RestApiServer.class);
    private final SystemMonitor monitor;
    private final RestServerConfig config;
    private final HttpServer httpServer;
    private final Gson gson;
    private final Supplier<AggregatedMetricsSnapshot> snapshotSupplier;
    private final long fallbackTimeoutSeconds;

    public RestApiServer(SystemMonitor monitor, RestServerConfig config,
                         Supplier<AggregatedMetricsSnapshot> snapshotSupplier,
                         long fallbackTimeoutSeconds) throws IOException {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.config = Objects.requireNonNull(config, "config");
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        this.fallbackTimeoutSeconds = fallbackTimeoutSeconds;
        this.gson = GsonFactory.gson();
        this.httpServer = HttpServer.create(new InetSocketAddress(this.config.port()), 0);
        registerContexts();
    }

    private void registerContexts() {
        httpServer.createContext("/identity", new IdentityHandler());
        httpServer.createContext("/metrics", new MetricsHandler());
        httpServer.createContext("/config", new ConfigHandler());
        httpServer.createContext("/config/interval", new IntervalUpdateHandler());
        httpServer.createContext("/config/processes", new ProcessListUpdateHandler());
    }

    public void start() {
        httpServer.start();
        LOG.info("[REST] Listening on port {}", config.port());
    }

    public void stop() {
        httpServer.stop(0);
    }

    @Override
    public void close() {
        stop();
    }

    private final class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            AggregatedMetricsSnapshot snapshot = snapshotSupplier.get();
            if (snapshot == null) {
                SystemMetrics metrics = monitor.getLatestMetrics();
                if (metrics == null) {
                    LOG.warn("[REST] /metrics solicitado antes de obtener la primera muestra");
                    sendJson(exchange, 503, Map.of("error", "Metrics not available yet"));
                    return;
                }
                snapshot = buildFallbackSnapshot(metrics);
            }
            sendJson(exchange, 200, snapshot);
        }
    }

    private final class IdentityHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            sendJson(exchange, 200, Map.of(
                    "instanceId", config.instanceId()
            ));
        }
    }

    private final class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("samplingIntervalSeconds", monitor.currentSamplingInterval().getSeconds());
            payload.put("processWatchList", monitorProcessList());
            payload.put("networkInterfaceExcludePatterns", monitor.currentInterfaceExcludePatterns());
            sendJson(exchange, 200, payload);
        }
    }

    private final class IntervalUpdateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "POST");
                return;
            }
            String body = readBody(exchange);
            if (body.isEmpty()) {
                sendJson(exchange, 400, Map.of("error", "Body required"));
                return;
            }
            try {
                var tree = gson.fromJson(body, Map.class);
                Object secondsValue = tree.get("seconds");
                if (secondsValue == null) {
                    sendJson(exchange, 400, Map.of("error", "Missing 'seconds' field"));
                    return;
                }
                long seconds = Math.round(Double.parseDouble(secondsValue.toString()));
                if (seconds <= 0) {
                    sendJson(exchange, 400, Map.of("error", "Interval must be positive"));
                    return;
                }
                monitor.updateSamplingInterval(Duration.ofSeconds(seconds));
                sendJson(exchange, 200, Map.of("samplingIntervalSeconds", monitor.currentSamplingInterval().getSeconds()));
            } catch (NumberFormatException ex) {
                sendJson(exchange, 400, Map.of("error", "Invalid seconds value"));
            }
        }
    }

    private final class ProcessListUpdateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "POST");
                return;
            }
            String body = readBody(exchange);
            if (body.isEmpty()) {
                sendJson(exchange, 400, Map.of("error", "Body required"));
                return;
            }
            try {
                var tree = gson.fromJson(body, Map.class);
                Object processes = tree.get("processNames");
                if (!(processes instanceof Iterable<?> iterable)) {
                    sendJson(exchange, 400, Map.of("error", "processNames must be an array"));
                    return;
                }
                var list = new java.util.ArrayList<String>();
                for (Object item : iterable) {
                    if (item != null) {
                        String value = item.toString().trim();
                        if (!value.isEmpty()) {
                            list.add(value);
                        }
                    }
                }
                monitor.setProcessWatchList(list);
                sendJson(exchange, 200, Map.of("processWatchList", monitorProcessList()));
            } catch (Exception ex) {
                sendJson(exchange, 400, Map.of("error", "Invalid JSON structure"));
            }
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private void sendMethodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().add("Allow", allowed);
        sendJson(exchange, 405, Map.of("error", "Method not allowed"));
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private java.util.List<String> monitorProcessList() {
        return monitor.currentProcessWatchList();
    }

    private AggregatedMetricsSnapshot buildFallbackSnapshot(SystemMetrics metrics) {
        double cpuLoad = metrics.cpuLoad();
        double temp = metrics.cpuTemperatureCelsius();
        double memRatio = metrics.usedMemoryRatio();
        long samplingSeconds = Math.max(1L, monitor.currentSamplingInterval().getSeconds());
        return new AggregatedMetricsSnapshot(
                config.instanceId(),
                Instant.now(),
                metrics.timestamp(),
                1,
                samplingSeconds,
                fallbackTimeoutSeconds,
                cpuLoad,
                cpuLoad,
                temp,
                temp,
                memRatio,
                memRatio,
                metrics.freeMemoryBytes(),
                metrics.totalMemoryBytes(),
                metrics.freeMemoryBytes(),
                metrics.totalMemoryBytes(),
                metrics.networkInterfaces(),
                metrics.watchedProcesses(),
                metrics
        );
    }
}
