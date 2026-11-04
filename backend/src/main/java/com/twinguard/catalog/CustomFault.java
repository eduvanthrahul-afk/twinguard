package com.twinguard.catalog;

import com.twinguard.sim.Firmware;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The Fault Lab: lets someone in the room type in their own bug, which proves the demo is not
 * scripted. Only the settings listed here can be changed, and only within these bounds.
 */
public final class CustomFault {

    public record Setting(String key, String label, String unit, double min, double max, double step,
                          double approved, BiConsumer<Firmware, Double> apply) {
    }

    public static final List<Setting> SETTINGS = List.of(
            new Setting("ttcFullBrakeS", "Full-brake time-to-collision", "s", 0.3, 3.0, 0.05, 1.2, (f, v) -> f.ttcFullBrakeS = v),
            new Setting("stopMargin", "Stopping-distance margin", "×", 0.5, 2.0, 0.05, 1.2, (f, v) -> f.stopMargin = v),
            new Setting("fullBrakeLevel", "Full-brake force", "×", 0.3, 1.0, 0.05, 1.0, (f, v) -> f.fullBrakeLevel = v),
            new Setting("sensorDelayMs", "Radar delay", "ms", 0, 500, 10, 0, (f, v) -> f.sensorDelayMs = v.intValue()),
            new Setting("debounceCycles", "Target confirmation", "samples", 1, 12, 1, 3, (f, v) -> f.debounceCycles = v.intValue()),
            new Setting("distanceBiasM", "Radar distance over-read", "m", 0, 15, 0.5, 0, (f, v) -> f.distanceBiasM = v),
            new Setting("actuatorLagS", "Brake actuator lag", "s", 0.05, 1.0, 0.01, 0.12, (f, v) -> f.actuatorLagS = v),
            new Setting("infotainmentPriority", "Infotainment gateway priority (0 is highest)", "", 0, 2, 1, 2, (f, v) -> f.infotainmentPriority = v.intValue()));

    private CustomFault() {
    }

    public static Firmware build(Map<String, Double> values) {
        Firmware firmware = Firmware.approved();
        for (Map.Entry<String, Double> e : values.entrySet()) {
            Setting s = SETTINGS.stream().filter(x -> x.key().equals(e.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown setting: " + e.getKey()));
            Double v = e.getValue();
            if (v == null || v.isNaN() || v < s.min() || v > s.max()) {
                throw new IllegalArgumentException(s.label() + " must be between " + s.min() + " and " + s.max());
            }
            s.apply().accept(firmware, v);
        }
        return firmware;
    }
}
