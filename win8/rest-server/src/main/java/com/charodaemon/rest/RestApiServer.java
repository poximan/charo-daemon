package com.charodaemon.rest;

import com.charodaemon.monitor.SystemMonitor;
import com.charodaemon.monitor.model.AggregatedMetricsSnapshot;
import com.charodaemon.monitor.model.SystemMetrics;
import com.charodaemon.monitor.model.TemperatureSensorReading;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
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
    private final Object scadaLock = new Object();
    private final Path configFilePath;
    private volatile ScadaAdapterRunner scadaAdapter;
    private volatile boolean scadaActive;
    private final java.util.List<String> scadaTags;
    private volatile java.util.List<Map<String, Object>> scadaSnapshotCache = java.util.List.of();
    private volatile Map<String, Map<String, Object>> scadaSnapshotCacheByTag = java.util.Map.of();

    public RestApiServer(SystemMonitor monitor, RestServerConfig config,
                         Supplier<AggregatedMetricsSnapshot> snapshotSupplier,
                         long fallbackTimeoutSeconds) throws IOException {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.config = Objects.requireNonNull(config, "config");
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        this.fallbackTimeoutSeconds = fallbackTimeoutSeconds;
        this.gson = GsonFactory.gson();
        this.httpServer = HttpServer.create(new InetSocketAddress(this.config.port()), 0);
        this.configFilePath = config.configFile();
        this.scadaActive = config.scadaEnabled();
        this.scadaTags = loadConfiguredTags();
        if (this.scadaActive && hasScadaConfig()) {
            this.scadaAdapter = createScadaRunner();
        } else {
            this.scadaAdapter = null;
            this.scadaActive = false;
        }
        registerContexts();
    }

    private void registerContexts() {
        httpServer.createContext("/identity", new IdentityHandler());
        httpServer.createContext("/metrics", new MetricsHandler());
        httpServer.createContext("/config", new ConfigHandler());
        httpServer.createContext("/config/interval", new IntervalUpdateHandler());
        httpServer.createContext("/config/processes", new ProcessListUpdateHandler());
        httpServer.createContext("/scada", new ScadaToggleHandler());
        httpServer.createContext("/scada/tags", new ScadaTagHandler());
    }

    public void start() {
        httpServer.start();
        LOG.info("[REST] Listening on port {}", config.port());
    }

    public void stop() {
        httpServer.stop(0);
        synchronized (scadaLock) {
            scadaActive = false;
            closeScadaAdapterLocked();
        }
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
                LOG.warn("[REST] /metrics solicitado sin ventana completa de agregacion");
                sendJson(exchange, 503, Map.of("error", "Aggregated metrics not available yet"));
                return;
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

    private final class ScadaTagHandler implements HttpHandler {
        private static final String BASE = "/scada/tags";
        private static final String PREFIX = BASE + "/";

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!hasScadaConfig()) {
                sendJson(exchange, 404, Map.of("error", "SCADA no configurado"));
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            ScadaAdapterRunner runner = currentScadaAdapter();
            if (runner == null) {
                Map<String, Object> summary = new HashMap<>();
                List<Map<String, Object>> cached = currentCachedSnapshots();
                summary.put("totalTags", scadaTags.size());
                summary.put("pendingTags", scadaTags.size());
                summary.put("message", "SCADA deshabilitado");
                summary.put("items", cached);
                summary.put("enabled", false);
                sendJson(exchange, 200, summary);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (BASE.equals(path) || (BASE + "/").equals(path)) {
                LOG.info("[SCADA] Consulta de listado de tags");
                Map<String, Object> summary = new HashMap<>(runner.buildSummary().asMap());
                List<Map<String, Object>> snapshots = runner.listSnapshots();
                cacheSnapshots(snapshots);
                summary.put("items", snapshots);
                summary.put("enabled", true);
                sendJson(exchange, 200, summary);
                return;
            }
            if (!path.startsWith(PREFIX) || path.length() <= PREFIX.length()) {
                sendJson(exchange, 400, Map.of("error", "Tag requerido en la URL"));
                return;
            }
            String encodedTag = path.substring(PREFIX.length());
            String tagName = URLDecoder.decode(encodedTag, StandardCharsets.UTF_8);
            if (tagName.isBlank()) {
                sendJson(exchange, 400, Map.of("error", "Tag requerido"));
                return;
            }
            LOG.info("[SCADA] Consulta puntual del tag {}", tagName);
            Map<String, Object> snapshot = runner.snapshotForTag(tagName);
            if (snapshot == null) {
                Map<String, Object> cached = cachedSnapshotForTag(tagName);
                if (cached == null) {
                    sendJson(exchange, 404, Map.of("error", "Tag no registrado"));
                    return;
                }
                cached = new HashMap<>(cached);
                cached.put("enabled", true);
                sendJson(exchange, 200, cached);
                return;
            }
            cacheSnapshot(snapshot);
            Map<String, Object> copy = new HashMap<>(snapshot);
            copy.put("enabled", true);
            sendJson(exchange, 200, copy);
        }
    }

    private final class ScadaToggleHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "PATCH");
                return;
            }
            if (!hasScadaConfig()) {
                sendJson(exchange, 409, Map.of("error", "SCADA no configurado en este host"));
                return;
            }
            String body = readBody(exchange);
            if (body.isEmpty()) {
                sendJson(exchange, 400, Map.of("error", "Body requerido"));
                return;
            }
            try {
                var tree = gson.fromJson(body, Map.class);
                Object enabledValue = tree.get("enabled");
                if (enabledValue == null) {
                    sendJson(exchange, 400, Map.of("error", "Campo 'enabled' requerido"));
                    return;
                }
                boolean desired = parseBooleanValue(enabledValue);
                ToggleResponse response = applyScadaToggle(desired);
                sendJson(exchange, 200, response.asMap());
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400, Map.of("error", ex.getMessage()));
            } catch (IOException ex) {
                LOG.error("[SCADA] Error actualizando estado SCADA", ex);
                sendJson(exchange, 500, Map.of("error", "No se pudo actualizar el estado SCADA"));
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

    private java.util.List<String> loadConfiguredTags() {
        if (!hasScadaConfig()) {
            return java.util.List.of();
        }
        Path script = config.scadaScriptPath().orElse(null);
        if (script == null) {
            return java.util.List.of();
        }
        Path tagsFile = script.getParent().resolve("tags.json");
        try {
            return ScadaAdapterRunner.loadTags(tagsFile);
        } catch (IOException ex) {
            LOG.warn("[SCADA] No se pudo leer tags.json: {}", ex.getMessage());
            return java.util.List.of();
        }
    }

    private java.util.List<Map<String, Object>> currentCachedSnapshots() {
        java.util.List<Map<String, Object>> cache = this.scadaSnapshotCache;
        return cache.isEmpty() ? placeholderSnapshots() : cache;
    }

    private void cacheSnapshots(List<Map<String, Object>> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> merged = new HashMap<>(this.scadaSnapshotCacheByTag);
        for (Map<String, Object> snapshot : snapshots) {
            if (snapshot == null) {
                continue;
            }
            Object tag = snapshot.get("tag");
            if (tag == null) {
                continue;
            }
            merged.put(tag.toString(), java.util.Collections.unmodifiableMap(new HashMap<>(snapshot)));
        }
        java.util.List<Map<String, Object>> ordered = new java.util.ArrayList<>(scadaTags.size());
        for (String tag : scadaTags) {
            Map<String, Object> entry = merged.get(tag);
            if (entry == null) {
                entry = defaultSnapshot(tag);
            }
            ordered.add(entry);
        }
        this.scadaSnapshotCacheByTag = java.util.Collections.unmodifiableMap(merged);
        this.scadaSnapshotCache = java.util.Collections.unmodifiableList(ordered);
    }

    private void cacheSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        cacheSnapshots(java.util.List.of(snapshot));
    }

    private Map<String, Object> cachedSnapshotForTag(String tag) {
        Map<String, Object> cached = this.scadaSnapshotCacheByTag.get(tag);
        if (cached != null) {
            return cached;
        }
        if (scadaTags.contains(tag)) {
            return defaultSnapshot(tag);
        }
        return null;
    }

    private java.util.List<Map<String, Object>> placeholderSnapshots() {
        java.util.List<Map<String, Object>> placeholders = new java.util.ArrayList<>();
        for (String tag : scadaTags) {
            placeholders.add(defaultSnapshot(tag));
        }
        return placeholders;
    }

    private Map<String, Object> defaultSnapshot(String tag) {
        return Map.of(
                "tag", tag,
                "state", "desconocido",
                "status", "unknown",
                "updatedAt", null,
                "message", ""
        );
    }

    private java.util.List<String> monitorProcessList() {
        return monitor.currentProcessWatchList();
    }

    private boolean hasScadaConfig() {
        return config.scadaScriptPath().isPresent() && config.powershellPath().isPresent();
    }

    private ScadaAdapterRunner currentScadaAdapter() {
        return scadaAdapter;
    }

    private ScadaAdapterRunner createScadaRunner() throws IOException {
        return new ScadaAdapterRunner(
                config.powershellPath().orElseThrow(),
                config.scadaScriptPath().orElseThrow(),
                config.scadaTimeout(),
                config.scadaPollInterval());
    }

    private void closeScadaAdapterLocked() {
        ScadaAdapterRunner adapter = this.scadaAdapter;
        this.scadaAdapter = null;
        if (adapter != null) {
            adapter.close();
        }
    }

    private ToggleResponse applyScadaToggle(boolean desired) throws IOException {
        synchronized (scadaLock) {
            boolean currentlyEnabled = scadaActive && scadaAdapter != null;
            if (desired == currentlyEnabled) {
                persistScadaFlag(desired);
                return new ToggleResponse(desired, "sin cambios");
            }
            if (desired) {
                ensureScadaResources();
                ScadaAdapterRunner runner = createScadaRunner();
                this.scadaAdapter = runner;
                this.scadaActive = true;
                persistScadaFlag(true);
                LOG.info("[SCADA] Adapter habilitado via PATCH");
                return new ToggleResponse(true, "SCADA habilitado");
            } else {
                closeScadaAdapterLocked();
                this.scadaActive = false;
                persistScadaFlag(false);
                LOG.info("[SCADA] Adapter deshabilitado via PATCH");
                return new ToggleResponse(false, "SCADA deshabilitado");
            }
        }
    }

    private void ensureScadaResources() throws IOException {
        Path script = config.scadaScriptPath().orElse(null);
        Path powershell = config.powershellPath().orElse(null);
        if (script == null || powershell == null) {
            throw new IOException("SCADA no configurado");
        }
        if (!Files.exists(script)) {
            throw new IOException("No existe el script configurado: " + script.toAbsolutePath());
        }
        if (!Files.exists(powershell)) {
            throw new IOException("No existe el ejecutable de PowerShell configurado: " + powershell.toAbsolutePath());
        }
    }

    private void persistScadaFlag(boolean enabled) throws IOException {
        if (configFilePath == null) {
            LOG.warn("[SCADA] No se puede persistir rest.scada.enabled sin archivo de config");
            return;
        }
        List<String> lines = Files.readAllLines(configFilePath, StandardCharsets.UTF_8);
        boolean updated = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            if ("rest.scada.enabled".equalsIgnoreCase(key)) {
                lines.set(i, "rest.scada.enabled=" + enabled);
                updated = true;
                break;
            }
        }
        if (!updated) {
            lines.add("rest.scada.enabled=" + enabled);
        }
        String newline = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            builder.append(lines.get(i));
            if (i < lines.size() - 1 || !lines.get(i).endsWith("\n")) {
                builder.append(newline);
            }
        }
        Files.writeString(configFilePath, builder.toString(), StandardCharsets.UTF_8);
    }

    private boolean parseBooleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            String normalized = str.trim().toLowerCase();
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
        }
        throw new IllegalArgumentException("Valor booleano invalido para 'enabled'");
    }

    private static final class ToggleResponse {
        private final boolean enabled;
        private final String message;

        ToggleResponse(boolean enabled, String message) {
            this.enabled = enabled;
            this.message = message;
        }

        Map<String, Object> asMap() {
            return Map.of(
                    "enabled", enabled,
                    "message", message
            );
        }
    }

}
