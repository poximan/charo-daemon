package com.charodaemon.monitor.model;

import java.util.Locale;

public record TemperatureSensorReading(
        String source,
        double celsius,
        boolean valid
) {
    private static final String DEFAULT_SOURCE = "sensor";

    public TemperatureSensorReading {
        if (source == null || source.isBlank()) {
            source = DEFAULT_SOURCE;
        }
    }

    public String formatLine() {
        if (valid && Double.isFinite(celsius)) {
            return String.format(Locale.ROOT, "%s: %.1f C", source, celsius);
        }
        return source + ": N/D";
    }
}
