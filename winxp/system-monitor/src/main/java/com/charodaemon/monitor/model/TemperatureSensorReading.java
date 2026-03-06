package com.charodaemon.monitor.model;

import java.util.Locale;

public final class TemperatureSensorReading {
    private final String source;
    private final double celsius;
    private final boolean valid;

    public TemperatureSensorReading(String source, double celsius, boolean valid) {
        this.source = (source == null || source.trim().length() == 0) ? "sensor" : source;
        this.celsius = celsius;
        this.valid = valid;
    }

    public String source() {
        return source;
    }

    public double celsius() {
        return celsius;
    }

    public boolean valid() {
        return valid;
    }

    public String formatLine() {
        if (valid && !Double.isNaN(celsius) && !Double.isInfinite(celsius)) {
            return String.format(Locale.ROOT, "%s: %.1f C", source, celsius);
        }
        return source + ": no disponible";
    }
}
