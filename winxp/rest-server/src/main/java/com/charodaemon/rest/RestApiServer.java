package com.charodaemon.rest;

import com.charodaemon.monitor.SystemMonitor;
import com.charodaemon.monitor.model.AggregatedMetricsSnapshot;
import com.charodaemon.rest.json.GsonFactory;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RestApiServer {
    private static final Logger LOG = LoggerFactory.getLogger(RestApiServer.class);

    private final SystemMonitor monitor;
    private final RestServerConfig config;
    private final HttpServer httpServer;
    private final Gson gson;
    private final SnapshotProvider snapshotProvider;

    public RestApiServer(SystemMonitor monitor, RestServerConfig config, SnapshotProvider snapshotProvider) throws IOException {
        if (monitor == null) {
            throw new IllegalArgumentException("monitor requerido");
        }
        if (config == null) {
            throw new IllegalArgumentException("config requerido");
        }
        if (snapshotProvider == null) {
            throw new IllegalArgumentException("snapshotProvider requerido");
        }
        this.monitor = monitor;
        this.config = config;
        this.snapshotProvider = snapshotProvider;
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
        LOG.info("[REST] Escuchando en puerto {}", Integer.valueOf(config.port()));
    }

    public void stop() {
        httpServer.stop(0);
    }

    public void close() {
        stop();
    }

    private final class MetricsHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            AggregatedMetricsSnapshot snapshot = snapshotProvider.latestSnapshot();
            if (snapshot == null) {
                LOG.warn("[REST] /metrics solicitado sin ventana completa de agregacion");
                sendJson(exchange, 503, singletonMap("error", "Aggregated metrics not available yet"));
                return;
            }
            sendJson(exchange, 200, snapshot);
        }
    }

    private final class IdentityHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            sendJson(exchange, 200, singletonMap("instanceId", config.instanceId()));
        }
    }

    private final class ConfigHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            Map<String, Object> payload = new HashMap<String, Object>();
            payload.put("samplingIntervalSeconds", Long.valueOf(monitor.currentSamplingIntervalSeconds()));
            payload.put("processWatchList", monitor.currentProcessWatchList());
            payload.put("networkInterfaceExcludePatterns", monitor.currentInterfaceExcludePatterns());
            sendJson(exchange, 200, payload);
        }
    }

    private final class IntervalUpdateHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "POST");
                return;
            }
            String body = readBody(exchange);
            if (body.length() == 0) {
                sendJson(exchange, 400, singletonMap("error", "Body required"));
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> tree = (Map<String, Object>) gson.fromJson(body, Map.class);
                Object secondsValue = tree.get("seconds");
                if (secondsValue == null) {
                    sendJson(exchange, 400, singletonMap("error", "Missing 'seconds' field"));
                    return;
                }
                long seconds = Math.round(Double.parseDouble(secondsValue.toString()));
                if (seconds <= 0L) {
                    sendJson(exchange, 400, singletonMap("error", "Interval must be positive"));
                    return;
                }
                monitor.updateSamplingIntervalSeconds(seconds);
                sendJson(exchange, 200, singletonMap("samplingIntervalSeconds", Long.valueOf(monitor.currentSamplingIntervalSeconds())));
            } catch (Exception ex) {
                sendJson(exchange, 400, singletonMap("error", "Invalid seconds value"));
            }
        }
    }

    private final class ProcessListUpdateHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "POST");
                return;
            }
            String body = readBody(exchange);
            if (body.length() == 0) {
                sendJson(exchange, 400, singletonMap("error", "Body required"));
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> tree = (Map<String, Object>) gson.fromJson(body, Map.class);
                Object processes = tree.get("processNames");
                if (!(processes instanceof Iterable)) {
                    sendJson(exchange, 400, singletonMap("error", "processNames must be an array"));
                    return;
                }
                List<String> list = new ArrayList<String>();
                for (Object item : (Iterable<?>) processes) {
                    if (item == null) {
                        continue;
                    }
                    String value = item.toString().trim();
                    if (value.length() > 0) {
                        list.add(value);
                    }
                }
                monitor.setProcessWatchList(list);
                sendJson(exchange, 200, singletonMap("processWatchList", monitor.currentProcessWatchList()));
            } catch (Exception ex) {
                sendJson(exchange, 400, singletonMap("error", "Invalid JSON structure"));
            }
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        InputStream input = null;
        ByteArrayOutputStream output = null;
        try {
            input = exchange.getRequestBody();
            output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), "UTF-8").trim();
        } finally {
            if (output != null) {
                output.close();
            }
            if (input != null) {
                input.close();
            }
        }
    }

    private void sendMethodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().add("Allow", allowed);
        sendJson(exchange, 405, singletonMap("error", "Method not allowed"));
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes("UTF-8");
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream out = null;
        try {
            out = exchange.getResponseBody();
            out.write(bytes);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private Map<String, Object> singletonMap(String key, Object value) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    public interface SnapshotProvider {
        AggregatedMetricsSnapshot latestSnapshot();
    }
}
