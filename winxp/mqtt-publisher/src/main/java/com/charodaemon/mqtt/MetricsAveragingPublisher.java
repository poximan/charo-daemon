package com.charodaemon.mqtt;

import com.charodaemon.monitor.SystemMonitor;
import com.charodaemon.monitor.model.AggregatedMetricsSnapshot;
import com.charodaemon.monitor.model.SystemMetrics;
import com.charodaemon.monitor.model.TemperatureSensorReading;
import com.charodaemon.rest.json.GsonFactory;
import com.google.gson.Gson;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocketFactory;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class MetricsAveragingPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(MetricsAveragingPublisher.class);

    private final SystemMonitor monitor;
    private final MqttPublisherConfig config;
    private final ScheduledExecutorService scheduler;
    private final Gson gson;
    private final MqttConnectOptions connectOptions;
    private final Deque<SystemMetrics> window = new ArrayDeque<SystemMetrics>();
    private final Object schedulingLock = new Object();
    private final Object mqttLock = new Object();

    private final String instanceId;
    private final int sampleWindow;
    private final int publishEveryHttpUpdates;
    private final long timeoutSeconds;

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile AggregatedMetricsSnapshot latestSnapshot;
    private volatile boolean mqttDisabled;
    private int updatesSincePublish;
    private MqttClient mqttClient;

    public MetricsAveragingPublisher(SystemMonitor monitor, MqttPublisherConfig config) {
        if (monitor == null) {
            throw new IllegalArgumentException("monitor requerido");
        }
        if (config == null) {
            throw new IllegalArgumentException("config requerido");
        }
        this.monitor = monitor;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new PublisherThreadFactory());
        this.gson = GsonFactory.gson();
        this.connectOptions = new MqttConnectOptions();
        this.connectOptions.setCleanSession(true);
        this.connectOptions.setUserName(config.username());
        this.connectOptions.setPassword(config.password());
        if (config.brokerUri().startsWith("ssl://")) {
            this.connectOptions.setSocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault());
        }

        this.instanceId = sanitizeInstanceId(config.clientId());
        this.sampleWindow = Math.max(1, config.sampleWindow());
        this.publishEveryHttpUpdates = Math.max(1, config.publishEveryHttpUpdates());
        this.timeoutSeconds = Math.max(1L, config.pollingIntervalSeconds()) * Math.max(sampleWindow, publishEveryHttpUpdates);
        this.mqttDisabled = false;
        this.updatesSincePublish = 0;
    }

    public void start() {
        try {
            connectMqtt();
        } catch (Exception ex) {
            mqttDisabled = true;
            LOG.error("[MQTT] Falla TLS/conexion, modulo mqtt queda inactivo y HTTP sigue activo", ex);
        }
        scheduleTask();
    }

    private void scheduleTask() {
        synchronized (schedulingLock) {
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
            }
            long periodSeconds = Math.max(1L, config.pollingIntervalSeconds());
            scheduledTask = scheduler.scheduleWithFixedDelay(new Runnable() {
                public void run() {
                    pollAndMaybePublishSafely();
                }
            }, 0L, periodSeconds, TimeUnit.SECONDS);
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
                if (window.size() < sampleWindow) {
                    return;
                }

                AggregatedMetricsSnapshot snapshot = buildSnapshot(window);
                latestSnapshot = snapshot;

                updatesSincePublish++;
                if (updatesSincePublish >= publishEveryHttpUpdates) {
                    updatesSincePublish = 0;
                    snapshotToPublish = snapshot;
                }
            }

            if (snapshotToPublish != null && !mqttDisabled) {
                publish(snapshotToPublish);
            }
        } catch (Exception ex) {
            LOG.error("[MQTT] Error en ciclo de publicacion", ex);
        }
    }

    private AggregatedMetricsSnapshot buildSnapshot(Deque<SystemMetrics> samples) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }

        int count = samples.size();
        double cpuSum = 0.0d;
        int cpuObserved = 0;
        double memRatioSum = 0.0d;
        long freeSum = 0L;
        long totalSum = 0L;

        for (SystemMetrics sample : samples) {
            double cpu = sample.cpuLoad();
            if (cpu >= 0.0d) {
                cpuSum += cpu;
                cpuObserved++;
            }
            memRatioSum += sample.usedMemoryRatio();
            freeSum += sample.freeMemoryBytes();
            totalSum += sample.totalMemoryBytes();
        }

        SystemMetrics latest = samples.getLast();
        double avgCpu = cpuObserved == 0 ? -1.0d : (cpuSum / cpuObserved);
        double avgMemRatio = count == 0 ? 0.0d : (memRatioSum / count);
        long avgFree = count == 0 ? 0L : (freeSum / count);
        long avgTotal = count == 0 ? 0L : (totalSum / count);
        long windowSeconds = Math.max(1L, config.pollingIntervalSeconds()) * count;

        return new AggregatedMetricsSnapshot(
                instanceId,
                utcNowIso(),
                latest.timestamp(),
                count,
                windowSeconds,
                timeoutSeconds,
                avgCpu,
                latest.cpuTemperatureCelsius(),
                avgMemRatio,
                avgFree,
                avgTotal,
                latest.networkInterfaces(),
                latest.watchedProcesses(),
                formatTemperatureReport(latest.temperatureSensors())
        );
    }

    private String formatTemperatureReport(List<TemperatureSensorReading> sensors) {
        if (sensors == null || sensors.isEmpty()) {
            return "no disponible";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sensors.size(); i++) {
            TemperatureSensorReading reading = sensors.get(i);
            if (i > 0) {
                sb.append(System.getProperty("line.separator"));
            }
            sb.append(reading.formatLine());
        }
        return sb.toString();
    }

    private void connectMqtt() throws MqttException {
        synchronized (mqttLock) {
            if (mqttDisabled) {
                return;
            }
            if (mqttClient == null) {
                mqttClient = new MqttClient(config.brokerUri(), config.clientId(), new MemoryPersistence());
                mqttClient.setCallback(new LoggingCallback());
            }
            if (!mqttClient.isConnected()) {
                if (config.availabilityEnabled()) {
                    String willTopic = resolveAvailabilityTopic();
                    if (willTopic != null) {
                        byte[] willBytes;
                        try {
                            willBytes = "offline".getBytes("UTF-8");
                        } catch (Exception ex) {
                            willBytes = "offline".getBytes();
                        }
                        connectOptions.setWill(willTopic, willBytes, 1, true);
                    }
                }
                mqttClient.connect(connectOptions);
                if (config.availabilityEnabled()) {
                    publishAvailability("online");
                }
            }
        }
    }

    private void ensureConnected() {
        if (mqttDisabled) {
            return;
        }
        try {
            connectMqtt();
        } catch (Exception ex) {
            mqttDisabled = true;
            LOG.error("[MQTT] Falla reconectando TLS/conexion, modulo mqtt queda inactivo", ex);
        }
    }

    private void publish(AggregatedMetricsSnapshot payload) {
        ensureConnected();
        synchronized (mqttLock) {
            if (mqttDisabled || mqttClient == null || !mqttClient.isConnected()) {
                return;
            }
            try {
                byte[] bytes = gson.toJson(payload).getBytes("UTF-8");
                MqttMessage message = new MqttMessage(bytes);
                message.setQos(1);
                mqttClient.publish(resolvePublishTopic(), message);
            } catch (Exception ex) {
                mqttDisabled = true;
                LOG.error("[MQTT] Falla publicando, modulo mqtt queda inactivo", ex);
            }
        }
    }

    private String resolvePublishTopic() {
        String raw = config.topicTemplate().replace("{clientId}", instanceId);
        return normalizeTopic(raw);
    }

    private String resolveAvailabilityTopic() {
        if (!config.availabilityEnabled()) {
            return null;
        }
        String raw = config.availabilityTopic();
        if (raw == null || raw.trim().length() == 0) {
            return null;
        }
        return normalizeTopic(raw.replace("{clientId}", instanceId));
    }

    private String normalizeTopic(String raw) {
        String topic = raw == null ? "" : raw.trim();
        while (topic.indexOf("//") >= 0) {
            topic = topic.replace("//", "/");
        }
        while (topic.startsWith("/")) {
            topic = topic.substring(1);
        }
        while (topic.endsWith("/")) {
            topic = topic.substring(0, topic.length() - 1);
        }
        return topic;
    }

    private String sanitizeInstanceId(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return "charodaemon";
        }
        return raw.trim().replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    public AggregatedMetricsSnapshot latestSnapshot() {
        return latestSnapshot;
    }

    public void signalOffline() {
        publishAvailability("offline");
    }

    private void publishAvailability(String status) {
        synchronized (mqttLock) {
            if (mqttDisabled || mqttClient == null || !mqttClient.isConnected()) {
                return;
            }
            String topic = resolveAvailabilityTopic();
            if (topic == null) {
                return;
            }
            try {
                byte[] payload;
                try {
                    payload = status.getBytes("UTF-8");
                } catch (Exception ex) {
                    payload = status.getBytes();
                }
                MqttMessage msg = new MqttMessage(payload);
                msg.setQos(1);
                msg.setRetained(config.retainAvailability());
                mqttClient.publish(topic, msg);
            } catch (Exception ex) {
                mqttDisabled = true;
                LOG.error("[MQTT] Falla publicando availability, modulo mqtt queda inactivo", ex);
            }
        }
    }

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
                } catch (Exception ex) {
                    LOG.warn("[MQTT] Error desconectando", ex);
                }
                try {
                    mqttClient.close();
                } catch (Exception ex) {
                    LOG.warn("[MQTT] Error cerrando cliente", ex);
                }
            }
        }
    }

    private String utcNowIso() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    private static final class PublisherThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "mqtt-publisher");
            t.setDaemon(true);
            return t;
        }
    }

    private static final class LoggingCallback implements MqttCallback {
        public void connectionLost(Throwable cause) {
            LOG.warn("[MQTT] Conexion perdida: {}", cause == null ? "unknown" : cause.toString());
        }

        public void messageArrived(String topic, MqttMessage message) {
            // no-op
        }

        public void deliveryComplete(IMqttDeliveryToken token) {
            // no-op
        }
    }
}
