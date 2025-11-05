package com.charodaemon.mqtt;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MetricsAveragingPublisher implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MetricsAveragingPublisher.class);
    private final RestMetricsClient metricsClient;
    private final MqttPublisherConfig config;
    private final ScheduledExecutorService scheduler;
    private final Gson gson;
    private final MqttConnectOptions connectOptions;
    private final String instanceId;

    private final Object schedulingLock = new Object();
    private final List<SystemMetrics> window = new ArrayList<>();
    private final Object mqttLock = new Object();

    private volatile Duration pollingInterval;
    private volatile ScheduledFuture<?> scheduledTask;
    private MqttClient mqttClient;

    public MetricsAveragingPublisher(RestMetricsClient metricsClient, MqttPublisherConfig config) {
        this.metricsClient = Objects.requireNonNull(metricsClient, "metricsClient");
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
        this.instanceId = sanitizeInstanceId(config.clientId());
    }

    public void start() {
        try {
            connectMqtt();
        } catch (MqttException e) {
            logMqttException("Unable to connect at startup", e);
        }
        metricsClient.fetchConfiguration().ifPresent(snapshot -> updateInterval(snapshot.samplingInterval()));
        scheduleTask();
    }

    private void connectMqtt() throws MqttException {
        synchronized (mqttLock) {
            if (mqttClient == null) {
                mqttClient = new MqttClient(config.brokerUri(), config.clientId(), new MemoryPersistence());
                mqttClient.setCallback(new LoggingCallback());
            }
            if (!mqttClient.isConnected()) {
                mqttClient.connect(connectOptions);
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
            metricsClient.fetchMetrics().ifPresent(sample -> {
                synchronized (window) {
                    window.add(sample);
                    if (window.size() >= config.sampleWindow()) {
                        AggregatedPayload payload = buildPayload(window);
                        window.clear();
                        publish(payload);
                        metricsClient.fetchConfiguration()
                                .ifPresent(snapshot -> updateInterval(snapshot.samplingInterval()));
                    }
                }
            });
        } catch (Exception ex) {
            LOG.error("[MQTT] Error during polling/publishing", ex);
        }
    }

    private AggregatedPayload buildPayload(List<SystemMetrics> samples) {
        int sampleCount = samples.size();
        double cpuSum = 0.0;
        int cpuObservations = 0;
        double temperatureSum = 0.0;
        int temperatureObservations = 0;
        double usedRatioSum = 0.0;
        long freeSum = 0L;
        long totalSum = 0L;

        for (SystemMetrics sample : samples) {
            if (sample.cpuLoad() >= 0.0) {
                cpuSum += sample.cpuLoad();
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
        long windowSeconds = pollingInterval.getSeconds() * sampleCount;

        SystemMetrics latest = samples.get(sampleCount - 1);

        return new AggregatedPayload(
                instanceId,
                Instant.now(),
                sampleCount,
                windowSeconds,
                avgCpu,
                avgTemperature,
                avgUsedRatio,
                avgFree,
                avgTotal,
                latest
        );
    }

    private void publish(AggregatedPayload payload) {
        ensureConnected();
        if (mqttClient == null || !mqttClient.isConnected()) {
            LOG.warn("[MQTT] Client is not connected; skipping publish");
            return;
        }
        byte[] bytes = gson.toJson(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MqttMessage message = new MqttMessage(bytes);
        message.setQos(1);
        try {
            mqttClient.publish(config.topic(), message);
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
        String message = exception.getMessage();
        LOG.error("[MQTT] {} (reason {}): {} [broker={}, clientId={}, topic={}]",
                context, reason, message, config.brokerUri(), config.clientId(), config.topic(), exception);
        // Stacktrace is included via last parameter above
    }

    public record AggregatedPayload(
            String instanceId,
            Instant generatedAt,
            int samples,
            long windowSeconds,
            double averageCpuLoad,
            double averageCpuTemperatureCelsius,
            double averageMemoryUsageRatio,
            long averageFreeMemoryBytes,
            long averageTotalMemoryBytes,
            SystemMetrics latestSample
    ) {
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
