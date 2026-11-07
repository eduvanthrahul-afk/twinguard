package com.twinguard.calibration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Model values fitted to recorded vehicle data, read from {@code /calibration/<name>.json} on the classpath.
 * When a file is missing, each model falls back to its design values and says that it is uncalibrated.
 */
public final class Calibration {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Calibration() {
    }

    public static Optional<JsonNode> load(String name) {
        try (InputStream in = Calibration.class.getResourceAsStream("/calibration/" + name + ".json")) {
            return in == null ? Optional.empty() : Optional.of(JSON.readTree(in));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** A number from the calibration file, or the design value when it isn't there. */
    public static double value(Optional<JsonNode> cal, String section, String key, double fallback) {
        return cal.map(c -> c.path(section).path(key)).filter(JsonNode::isNumber).map(JsonNode::asDouble).orElse(fallback);
    }

    /** One line saying where the model's values came from, shown next to the results. */
    public static String summary(Optional<JsonNode> cal) {
        return cal.map(c -> c.path("summary").asText("")).filter(s -> !s.isBlank()).orElse("Uncalibrated: design values, not fitted to recorded vehicle data.");
    }
}
