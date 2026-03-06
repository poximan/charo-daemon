package com.charodaemon.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class ScadaAdapterRunner implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ScadaAdapterRunner.class);
    private final Path powershellPath;
    private final Path scriptPath;
    private final Duration timeout;
    private final Duration pollInterval;
    private final List<String> tagOrder;
    private final Map<String, TagSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Thread pollingThread;
    private volatile boolean running = true;

    ScadaAdapterRunner(Path powershellPath, Path scriptPath, Duration timeout, Duration pollInterval) throws IOException {
        this.powershellPath = Objects.requireNonNull(powershellPath, "powershellPath");
        this.scriptPath = Objects.requireNonNull(scriptPath, "scriptPath");
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.pollInterval = pollInterval == null ? Duration.ofSeconds(30) : pollInterval;
        this.tagOrder = Collections.unmodifiableList(loadTags(scriptPath.getParent().resolve("tags.json")));
        for (String tag : tagOrder) {
            snapshots.put(tag, new TagSnapshot(tag));
        }
        this.pollingThread = new Thread(this::pollLoop, "scada-poller");
        this.pollingThread.setDaemon(true);
        this.pollingThread.start();
    }

    static List<String> loadTags(Path tagsFile) throws IOException {
        if (!Files.exists(tagsFile)) {
            throw new IOException("No existe tags.json en " + tagsFile.toAbsolutePath());
        }
        try (Reader reader = Files.newBufferedReader(tagsFile, StandardCharsets.UTF_8)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            List<String> tags = new ArrayList<>();
            for (JsonElement element : array) {
                String tagValue = null;
                if (element.isJsonObject()) {
                    JsonObject obj = element.getAsJsonObject();
                    JsonElement tagEntry = obj.get("tag");
                    if (tagEntry != null && !tagEntry.getAsString().isBlank()) {
                        tagValue = tagEntry.getAsString().trim();
                    }
                } else if (element.isJsonPrimitive()) {
                    String primitive = element.getAsString();
                    if (primitive != null && !primitive.isBlank()) {
                        tagValue = primitive.trim();
                    }
                }
                if (tagValue != null && !tagValue.isEmpty()) {
                    tags.add(tagValue);
                }
            }
            if (tags.isEmpty()) {
                LOG.warn("[SCADA] tags.json no contiene tags validos");
            }
            return tags;
        }
    }

    private void pollLoop() {
        if (tagOrder.isEmpty()) {
            LOG.warn("[SCADA] No hay tags configurados, se omite el loop de sondeo");
            return;
        }
        while (running) {
            for (String tag : tagOrder) {
                if (!running) {
                    break;
                }
                updateTag(tag);
            }
            try {
                Thread.sleep(Math.max(1000L, pollInterval.toMillis()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void updateTag(String tag) {
        TagSnapshot snapshot = snapshots.get(tag);
        if (snapshot == null) {
            return;
        }
        LOG.info("[SCADA] Solicitud de muestra para {}", tag);
        try {
            JsonObject payload = executeScript(tag);
            String value = payload.has("state") ? payload.get("state").getAsString() : "";
            String timestamp = payload.has("timestamp") ? payload.get("timestamp").getAsString() : Instant.now().toString();
            snapshot.updateOk(value, timestamp);
            LOG.info("[SCADA] Tag {} actualizado -> estado {}", tag, snapshot.state);
        } catch (Exception ex) {
            LOG.warn("[SCADA] Error actualizando tag {}: {}", tag, ex.getMessage());
            snapshot.updateError(ex.getMessage());
        }
    }

    List<String> getTagOrder() {
        return tagOrder;
    }

    List<Map<String, Object>> listSnapshots() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String tag : tagOrder) {
            TagSnapshot snapshot = snapshots.get(tag);
            if (snapshot != null) {
                items.add(snapshot.toMap());
            }
        }
        return items;
    }

    Map<String, Object> snapshotForTag(String tag) {
        TagSnapshot snapshot = snapshots.get(tag);
        return snapshot != null ? snapshot.toMap() : null;
    }

    SnapshotSummary buildSummary() {
        int total = tagOrder.size();
        int pending = 0;
        for (String tag : tagOrder) {
            TagSnapshot snapshot = snapshots.get(tag);
            if (snapshot != null && !snapshot.hasData()) {
                pending++;
            }
        }
        String message = pending > 0
                ? "restan " + pending + " tags para completar el ciclo"
                : "ciclo completo";
        return new SnapshotSummary(total, pending, message);
    }

    private JsonObject executeScript(String tag) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(powershellPath.toString());
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(scriptPath.toString());
        command.add("-TagName");
        command.add(tag);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(scriptPath.getParent().toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Tiempo de espera excedido para scada tag " + tag);
        }
        String stdout = readAll(process.getInputStream());
        String stderr = readAll(process.getErrorStream());
        if (!stderr.isBlank()) {
            LOG.warn("[SCADA] stderr para tag {}: {}", tag, stderr);
        }
        if (stdout.isBlank()) {
            throw new IOException("Respuesta vacia del adapter SCADA");
        }
        JsonObject payload = JsonParser.parseString(stdout).getAsJsonObject();
        payload.addProperty("exitCode", process.exitValue());
        return payload;
    }

    @Override
    public void close() {
        running = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
            try {
                pollingThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static final class TagSnapshot {
        private final String tag;
        private volatile String state = "desconocido";
        private volatile String status = "unknown";
        private volatile String updatedAt = null;
        private volatile String message = "";

        TagSnapshot(String tag) {
            this.tag = tag;
        }

        boolean hasData() {
            return !"unknown".equals(status) || updatedAt != null;
        }

        void updateOk(String value, String timestamp) {
            this.state = value == null || value.isBlank() ? "desconocido" : value;
            this.status = "ok";
            this.message = "";
            this.updatedAt = timestamp;
        }

        void updateError(String error) {
            this.status = "error";
            this.message = error == null ? "" : error;
            this.state = "desconocido";
            this.updatedAt = Instant.now().toString();
        }

        Map<String, Object> toMap() {
            return Map.of(
                    "tag", tag,
                    "state", state,
                    "status", status,
                    "updatedAt", updatedAt,
                    "message", message
            );
        }
    }

    static final class SnapshotSummary {
        final int totalTags;
        final int pendingTags;
        final String message;

        SnapshotSummary(int totalTags, int pendingTags, String message) {
            this.totalTags = totalTags;
            this.pendingTags = pendingTags;
            this.message = message;
        }

        Map<String, Object> asMap() {
            return Map.of(
                    "totalTags", totalTags,
                    "pendingTags", pendingTags,
                    "message", message
            );
        }
    }
}
