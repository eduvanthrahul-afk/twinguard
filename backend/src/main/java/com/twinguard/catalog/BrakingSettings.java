package com.twinguard.catalog;

import com.twinguard.sim.Firmware;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

/**
 * The braking firmware settings a release package may change, and the range each may take.
 * The ranges keep a package inside what the simulator can run, not inside what is safe: an unsafe
 * value is exactly what the gate is there to catch.
 */
public final class BrakingSettings {

    public enum Kind { NUMBER, INTEGER, BOOLEAN }

    public record Bound(String key, String label, Kind kind, double min, double max) {
    }

    public static final List<Bound> ALL = List.of(
            number("ttcPreBrakeS", "Pre-brake time-to-collision (s)", 0.2, 6),
            number("ttcFullBrakeS", "Full-brake time-to-collision (s)", 0.1, 6),
            number("stopMargin", "Stopping-distance margin (×)", 0.1, 5),
            number("stopBufferM", "Stopping buffer (m)", 0, 20),
            number("preBrakeLevel", "Pre-brake force (×)", 0, 1),
            number("fullBrakeLevel", "Full-brake force (×)", 0, 1),
            number("minActiveKph", "Lowest active speed (km/h)", 0, 100),
            number("maxActiveKph", "Highest active speed (km/h)", 10, 300),
            bool("flipTtcFullCompare", "Inverted full-brake comparison"),
            bool("flipStopDistanceCompare", "Inverted stopping-distance comparison"),
            bool("latchFullBrake", "Hold full braking once started"),
            bool("frictionAware", "Adjust for road friction"),
            number("frictionFloor", "Friction floor", 0, 1),
            number("frictionBias", "Friction estimate bias", -0.5, 0.5),
            integer("debounceCycles", "Target confirmation (samples)", 1, 50),
            integer("filterWindow", "Radar filter window (samples)", 1, 50),
            integer("sensorDelayMs", "Radar delay (ms)", 0, 2000),
            number("distanceBiasM", "Radar distance over-read (m)", -20, 20),
            number("speedScale", "Speed reading scale (×)", 0.5, 2),
            number("rangeLimitM", "Radar range (m)", 10, 300),
            integer("staleTimeoutMs", "Stale radar timeout (ms)", 10, 5000),
            number("actuatorLagS", "Brake actuator lag (s)", 0.01, 2),
            integer("sensorPriority", "Radar gateway priority (0 is highest)", 0, 2),
            integer("brakePriority", "Brake gateway priority (0 is highest)", 0, 2),
            integer("infotainmentPriority", "Infotainment gateway priority (0 is highest)", 0, 2),
            number("infotainmentRate", "Infotainment traffic rate (×)", 0, 5),
            integer("gatewayCapacity", "Gateway messages per cycle", 1, 64),
            integer("gatewayQueueLimit", "Gateway queue limit", 1, 4096));

    private BrakingSettings() {
    }

    private static Bound number(String key, String label, double min, double max) {
        return new Bound(key, label, Kind.NUMBER, min, max);
    }

    private static Bound integer(String key, String label, double min, double max) {
        return new Bound(key, label, Kind.INTEGER, min, max);
    }

    private static Bound bool(String key, String label) {
        return new Bound(key, label, Kind.BOOLEAN, 0, 1);
    }

    public static Optional<Bound> find(String key) {
        return ALL.stream().filter(b -> b.key().equals(key)).findFirst();
    }

    /** The approved v1 value of a setting, as a number. Booleans are 1 for true. */
    public static double approved(Bound bound) {
        return read(Firmware.approved(), bound.key());
    }

    static double read(Firmware firmware, String key) {
        try {
            Object v = Firmware.class.getField(key).get(firmware);
            return v instanceof Boolean b ? (b ? 1 : 0) : ((Number) v).doubleValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Firmware has no setting " + key, e);
        }
    }

    static void write(Firmware firmware, Bound bound, double value) {
        try {
            Field f = Firmware.class.getField(bound.key());
            switch (bound.kind()) {
                case BOOLEAN -> f.setBoolean(firmware, value != 0);
                case INTEGER -> f.setInt(firmware, (int) value);
                case NUMBER -> f.setDouble(firmware, value);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Firmware has no setting " + bound.key(), e);
        }
    }
}
