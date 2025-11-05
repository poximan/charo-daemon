package com.charodaemon.mqtt;

import com.charodaemon.monitor.model.SystemMetrics;
import com.charodaemon.rest.json.GsonFactory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class RestMetricsClient {
    private final HttpClient httpClient;
    private final URI metricsUri;
    private final URI configUri;
    private final Gson gson;

    public RestMetricsClient(URI baseUri) {
        this.httpClient = HttpClient.newHttpClient();
        this.metricsUri = baseUri.resolve("/metrics");
        this.configUri = baseUri.resolve("/config");
        this.gson = GsonFactory.gson();
    }

    public Optional<SystemMetrics> fetchMetrics() {
        HttpRequest request = HttpRequest.newBuilder(metricsUri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.ofNullable(gson.fromJson(response.body(), SystemMetrics.class));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<RestConfigSnapshot> fetchConfiguration() {
        HttpRequest request = HttpRequest.newBuilder(configUri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            long intervalSeconds = json.get("samplingIntervalSeconds").getAsLong();
            List<String> processes = json.has("processWatchList") && json.get("processWatchList").isJsonArray()
                    ? gson.fromJson(json.get("processWatchList"), new TypeToken<List<String>>() {
            }.getType())
                    : List.of();
            return Optional.of(new RestConfigSnapshot(Duration.ofSeconds(intervalSeconds), processes));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public record RestConfigSnapshot(Duration samplingInterval, List<String> processes) {
    }
}
