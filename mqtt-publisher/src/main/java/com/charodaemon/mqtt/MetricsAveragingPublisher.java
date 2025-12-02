package com.charodaemon.mqtt;

import com.charodaemon.monitor.SystemMonitor;
import com.charodaemon.monitor.model.SystemMetrics;
import com.charodaemon.rest.json.GsonFactory;
import com.google.gson.Gson;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.net.ssl.SSLSocketFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.charodaemon.monitor.model.AggregatedMetricsSnapshot;

public final class MetricsAveragingPublisher implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MetricsAveragingPublisher.class);
    private final SystemMonitor monitor;
    private final MqttPublisherConfig config;
    private final ScheduledExecutorService scheduler;
    private final Gson gson;
    private final MqttConnectOptions connectOptions;
    private final String instanceId;

    private final Object schedulingLock = new Object();
    private final Deque<SystemMetrics> window = new ArrayDeque<>();
    private final Object mqttLock = new Object();

    private volatile Duration pollingInterval;
    private volatile ScheduledFuture<?> scheduledTask;
    private MqttClient mqttClient;
    private volatile AggregatedMetricsSnapshot latestSnapshot;
    private final int sampleWindow;
    private final long timeoutSeconds;
    private int samplesSincePublish = 0;

    public MetricsAveragingPublisher(SystemMonitor monitor, MqttPublisherConfig config) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new PublisherThreadFactory());
        this.gson = GsonFactory.gson();
        this.connectOptions = new MqttConnectOptions();
        this.connectOptions.setAutomaticReconnect(true);
        this.connectOptions.setCleanSession(true);
        config.username().ifPresent(connectOptions::setUserName);
        config.password().ifPresent(connectOptions::setPassword);
        if (config.brokerUri().startsWith("ssl://")) {
            this.connectOptions.setSocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault());
        }
        this.pollingInterval = config.pollingInterval();
        this.sampleWindow = Math.max(1, config.sampleWindow());
        long pollSeconds = Math.max(1L, this.pollingInterval.getSeconds());
        this.timeoutSeconds = pollSeconds * this.sampleWindow;
        this.instanceId = sanitizeInstanceId(config.clientId());
    }

    public void start() {
        try {
            connectMqtt();
        } catch (MqttException e) {
            logMqttException("Unable to connect at startup", e);
        }
        scheduleTask();
    }

    private void connectMqtt() throws MqttException {
        synchronized (mqttLock) {
            if (mqttClient == null) {
                mqttClient = new MqttClient(config.brokerUri(), config.clientId(), new MemoryPersistence());
                mqttClient.setCallback(new LoggingCallback());
            }
            if (!mqttClient.isConnected()) {
                // Configure LWT (availability) if enabled
                if (config.availabilityEnabled()) {
                    String willTopic = resolveAvailabilityTopic();
                    if (willTopic != null) {
                        MqttMessage will = new MqttMessage("offline".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        will.setQos(1);
                        will.setRetained(true);
                        connectOptions.setWill(willTopic, will.getPayload(), will.getQos(), will.isRetained());
                    }
                }
                mqttClient.connect(connectOptions);
                // Publish ONLINE retained status after successful connect
                if (config.availabilityEnabled()) {
                    publishAvailability("online");
                }
            }
        }
    }

    private void scheduleTask() {
        synchronized (schedulingLock) {
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
            }
            long periodMillis = Math.max(1000L, pollingInterval.toMillis());
            scheduledTask = scheduler.scheduleAtFixedRate(
                    this::pollAndMaybePublishSafely,
                    0,
                    periodMillis,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void pollAndMaybePublishSafely() {
        try {
            SystemMetrics sample = monitor.getLatestMetrics();
            if (sample == null) {
                return;
            }
            AggregatedMetricsSnapshot snapshotToPublish = null;
            synchronized (window) {
                window.addLast(sample);
                if (window.size() > sampleWindow) {
                    window.removeFirst();
                }
                AggregatedMetricsSnapshot snapshot = buildSnapshot(window);
                latestSnapshot = snapshot;
                samplesSincePublish++;
                if (snapshot != null && window.size() == sampleWindow && samplesSincePublish >= sampleWindow) {
                    snapshotToPublish = snapshot;
                    samplesSincePublish = 0;
                }
            }
            if (snapshotToPublish != null) {
                publish(snapshotToPublish);
            }
        } catch (Exception ex) {
            LOG.error("[MQTT] Error during polling/publishing", ex);
        }
    }

    private AggregatedMetricsSnapshot buildSnapshot(Deque<SystemMetrics> samples) {
        if (samples.isEmpty()) {
            return null;
        }
        int sampleCount = samples.size();
        double cpuSum = 0.0;
        int cpuObservations = 0;
        double temperatureSum = 0.0;
        int temperatureObservations = 0;
        double usedRatioSum = 0.0;
        long freeSum = 0L;
        long totalSum = 0L;

        for (SystemMetrics sample : samples) {
            double cpuLoad = sample.cpuLoad();
            if (cpuLoad >= 0.0) {
                cpuSum += cpuLoad;
                cpuObservations++;
            }
            double temperature = sample.cpuTemperatureCelsius();
            if (temperature >= 0.0) {
                temperatureSum += temperature;
                temperatureObservations++;
            }
            usedRatioSum += sample.usedMemoryRatio();
            freeSum += sample.freeMemoryBytes();
            totalSum += sample.totalMemoryBytes();
        }

        double avgCpu = cpuObservations == 0 ? -1.0 : cpuSum / cpuObservations;
        double avgTemperature = temperatureObservations == 0 ? -1.0 : temperatureSum / temperatureObservations;
        double avgUsedRatio = sampleCount == 0 ? 0.0 : usedRatioSum / sampleCount;
        long avgFree = sampleCount == 0 ? 0L : freeSum / sampleCount;
        long avgTotal = sampleCount == 0 ? 0L : totalSum / sampleCount;
        long windowSeconds = Math.max(1L, pollingInterval.getSeconds()) * sampleCount;

        SystemMetrics latest = samples.getLast();
        double latestCpu = latest.cpuLoad();
        double latestTemp = latest.cpuTemperatureCelsius();
        double latestMemRatio = latest.usedMemoryRatio();
        long latestFree = latest.freeMemoryBytes();
        long latestTotal = latest.totalMemoryBytes();

        return new AggregatedMetricsSnapshot(
                instanceId,
                Instant.now(),
                latest.timestamp(),
                sampleCount,
                windowSeconds,
                timeoutSeconds,
                latestCpu,
                avgCpu,
                latestTemp,
                avgTemperature,
                latestMemRatio,
                avgUsedRatio,
                latestFree,
                latestTotal,
                avgFree,
                avgTotal,
                latest.networkInterfaces(),
                latest.watchedProcesses(),
                latest
        );
    }

    private void publish(AggregatedMetricsSnapshot payload) {
        ensureConnected();
        if (mqttClient == null || !mqttClient.isConnected()) {
            LOG.warn("[MQTT] Client is not connected; skipping publish");
            return;
        }
        byte[] bytes = gson.toJson(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MqttMessage message = new MqttMessage(bytes);
        message.setQos(1);
        String topic = resolvePublishTopic();
        try {
            mqttClient.publish(topic, message);
        } catch (MqttException e) {
            logMqttException("Failed to publish message", e);
        }
    }

    private void ensureConnected() {
        try {
            connectMqtt();
        } catch (MqttException e) {
            logMqttException("Failed to establish connection", e);
        }
    }

    private void updateInterval(Duration newInterval) {
        if (newInterval == null || newInterval.isZero() || newInterval.isNegative()) {
            return;
        }
        if (!newInterval.equals(this.pollingInterval)) {
            this.pollingInterval = newInterval;
            scheduleTask();
        }
    }

    @Override
    public void close() {
        synchronized (schedulingLock) {
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTask = null;
            }
        }
        scheduler.shutdown();
        signalOffline();
        synchronized (mqttLock) {
            if (mqttClient != null) {
                try {
                    if (mqttClient.isConnected()) {
                        mqttClient.disconnect();
                    }
                    mqttClient.close();
                } catch (MqttException e) {
                    logMqttException("Failed to close MQTT client", e);
                }
            }
        }
    }

    private static final class PublisherThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "mqtt-publisher");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static String sanitizeInstanceId(String raw) {
        if (raw == null) {
            return "charodaemon";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "charodaemon";
        }
        return trimmed.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    private void logMqttException(String context, MqttException exception) {
        int reason = exception.getReasonCode();
        String humanReadable = describeReason(reason);
        String causeText = describeCause(exception);
        String broker = config.brokerUri();
        String clientId = config.clientId();
        String topic = resolvePublishTopic();
        if (reason == MqttException.REASON_CODE_CONNECTION_LOST) {
            LOG.warn("[MQTT] {} - {} (reason {}) [broker={}, clientId={}, topic={}, cause={}]",
                    context, humanReadable, reason, broker, clientId, topic, causeText);
        } else {
            LOG.error("[MQTT] {} - {} (reason {}) [broker={}, clientId={}, topic={}, cause={}]",
                    context, humanReadable, reason, broker, clientId, topic, causeText);
        }
    }

    private static String describeCause(Throwable throwable) {
        if (throwable == null) {
            return "desconocida";
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String type = root.getClass().getSimpleName();
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return type;
        }
        return type + ": " + message;
    }

    private static String describeReason(int reasonCode) {
        switch (reasonCode) {
            case MqttException.REASON_CODE_CONNECTION_LOST:
                return "conexion perdida";
            case MqttException.REASON_CODE_CLIENT_CONNECTED:
                return "cliente ya conectado";
            case MqttException.REASON_CODE_CLIENT_ALREADY_DISCONNECTED:
                return "cliente ya desconectado";
            case MqttException.REASON_CODE_CLIENT_NOT_CONNECTED:
                return "cliente no conectado";
            case MqttException.REASON_CODE_CLIENT_DISCONNECTING:
                return "cliente en desconexion";
            case MqttException.REASON_CODE_MAX_INFLIGHT:
                return "limite de mensajes en vuelo";
            case MqttException.REASON_CODE_SERVER_CONNECT_ERROR:
                return "error al conectar con el broker";
            default:
                return "codigo " + reasonCode;
        }
    }

    private String resolvePublishTopic() {
        String templated = config.topicTemplate().replace("{clientId}", instanceId);
        return normalizeTopic(templated);
    }

    private String resolveAvailabilityTopic() {
        if (!config.availabilityEnabled()) return null;
        String topic = null;
        if (config.availabilityTopic().isPresent()) {
            topic = config.availabilityTopic().get();
        }
        if (topic == null) return null;
        return normalizeTopic(topic.replace("{clientId}", instanceId));
    }

    public void signalOffline() {
        synchronized (mqttLock) {
            publishAvailability("offline");
        }
    }

    private void publishAvailability(String status) {
        String topic = resolveAvailabilityTopic();
        if (topic == null || mqttClient == null || !mqttClient.isConnected()) return;
        try {
            MqttMessage msg = new MqttMessage(status.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            msg.setQos(1);
            msg.setRetained(config.retainAvailability());
            mqttClient.publish(topic, msg);
        } catch (MqttException e) {
            logMqttException("Failed to publish availability", e);
        }
    }

    private static String normalizeTopic(String raw) {
        String t = raw.trim();
        while (t.contains("//")) {
            t = t.replace("//", "/");
        }
        if (t.startsWith("/")) t = t.substring(1);
        if (t.endsWith("/")) t = t.substring(0, t.length()-1);
        return t;
    }

    public AggregatedMetricsSnapshot latestSnapshot() {
        return latestSnapshot;
    }

    private static final class LoggingCallback implements MqttCallback {
        @Override
        public void connectionLost(Throwable cause) {
            LOG.warn("[MQTT] Connection lost: {}", (cause != null ? cause.toString() : "unknown"));
        }

        @Override
        public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
            // No-op; publisher only.
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // No-op
        }
    }
}
