package com.twinguard.feature;

import com.twinguard.feature.logic.LaneLogic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Lane keeping on a curved road with crosswind, using a kinematic bicycle model. */
public final class LaneFeature implements VehicleFeature {

    private static final double DT = 0.02;
    private static final double DURATION = 15;
    private static final double WHEELBASE = 2.8;
    private static final double G = 9.81;
    private static final double STEER_LAG = 0.08;
    private static final double MAX_ROAD_WHEEL = 0.5;
    private static final int LATENCY_STEPS = 3;
    private static final double LANE_MARGIN = 0.85;
    private static final double OFFSET_TOLERANCE = 0.1;
    private static final double QUIET_STEER_LIMIT = 0.02;
    private static final double STEER_ACTIVITY_RATIO = 1.5;
    private static final int TRACE_EVERY = 10;

    @Override
    public String id() {
        return "lane";
    }

    @Override
    public String name() {
        return "Lane keeping";
    }

    @Override
    public String summary() {
        return "Steers to stay in lane through curves and crosswind.";
    }

    @Override
    public String limitations() {
        return "A kinematic bicycle model with simulated lane position. It doesn't cover camera perception, missing or faded lane lines, tyre slip dynamics, or lane changes.";
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param("speedKph", "Speed", "km/h", 40, 130, 90),
                new Param("curvature", "Road curvature", "1/m", -0.01, 0.01, 0),
                new Param("wind", "Crosswind push", "m/s²", 0, 1.5, 0),
                new Param("startOffset", "Starting offset", "m", -0.5, 0.5, 0),
                new Param("friction", "Road friction", "", 0.3, 1.0, 0.8),
                new Param("noise", "Lane position noise", "m", 0, 0.1, 0));
    }

    @Override
    public int coverageX() {
        return 0;
    }

    @Override
    public int coverageY() {
        return 1;
    }

    @Override
    public List<Channel> channels() {
        return List.of(
                new Channel("offset", "Lane offset", "m"),
                new Channel("steer", "Steering angle", "rad"),
                new Channel("heading", "Heading error", "rad"),
                new Channel("lateral", "Lateral acceleration", "m/s²"));
    }

    @Override
    public double stepSeconds() {
        return DT;
    }

    @Override
    public List<String> inputNames() {
        return List.of("lane_offset", "heading_error", "speed", "curvature");
    }

    @Override
    public List<String> outputNames() {
        return List.of("steer_command");
    }

    @Override
    public Map<String, Double> defaultSettings() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("offsetGain", 2.5);
        s.put("headingGain", 1.2);
        s.put("feedForward", 1.0);
        s.put("deadband", 0.0);
        s.put("maxSteer", 0.3);
        s.put("filter", 0.5);
        s.put("minSpeed", 8.0);
        return s;
    }

    @Override
    public FeatureController logic(Map<String, Double> settings) {
        return new LaneLogic(settings);
    }

    @Override
    public Class<? extends FeatureController> logicClass() {
        return LaneLogic.class;
    }

    @Override
    public List<SettingFault> faults() {
        List<SettingFault> f = new ArrayList<>();
        for (double v : new double[] {-1.0, 0.1, 0.5, 1.5, 8.0}) {
            f.add(new SettingFault("offset-gain-" + v, "Steering gains", "Offset gain set to " + v, Map.of("offsetGain", v)));
        }
        for (double v : new double[] {-1.0, 0.1, 0.5, 4.0}) {
            f.add(new SettingFault("heading-gain-" + v, "Steering gains", "Heading gain set to " + v, Map.of("headingGain", v)));
        }
        for (double v : new double[] {-1.0, 0.0, 0.5, 0.9}) {
            f.add(new SettingFault("feed-forward-" + v, "Curve handling", "Curve feed-forward set to ×" + v, Map.of("feedForward", v)));
        }
        for (double v : new double[] {0.1, 0.3, 0.6}) {
            f.add(new SettingFault("deadband-" + v, "Sensor processing", "Offset deadband set to " + v + " m", Map.of("deadband", v)));
        }
        for (double v : new double[] {0.01, 0.03, 0.1}) {
            f.add(new SettingFault("max-steer-" + v, "Limits", "Steering limit set to " + v + " rad", Map.of("maxSteer", v)));
        }
        for (double v : new double[] {0.02, 0.1, 1.0}) {
            f.add(new SettingFault("filter-" + v, "Sensor processing", "Offset filter set to " + v, Map.of("filter", v)));
        }
        for (double v : new double[] {15.0, 25.0, 35.0}) {
            f.add(new SettingFault("min-speed-" + v, "Limits", "Inactive below " + v + " m/s", Map.of("minSpeed", v)));
        }
        return f;
    }

    @Override
    public List<Release> releases() {
        return List.of(
                new Release("lka-3.1-relaxed", "LKA 3.1 Relaxed Steering", "Less steering correction, for a more natural feel.",
                        Map.of("offsetGain", 0.4, "headingGain", 0.5), Set.of(), "No behaviour change declared"),
                new Release("lka-3.2-tuning", "LKA 3.2 Steering Filter", "Filters lane position slightly more. This change is declared.",
                        Map.of("filter", 0.4), Set.of("MORE_STEERING"), "Steering activity may change slightly"),
                new Release("lka-3.3-highway", "LKA 3.3 Highway Comfort", "Ignores small lane offsets, so highway driving feels calmer.",
                        Map.of("deadband", 0.6), Set.of(), "No behaviour change declared"));
    }

    /** True when this curve and wind are within what the tyres can hold. */
    static boolean holdable(double[] s) {
        double v = s[0] / 3.6;
        return v * v * Math.abs(s[1]) + s[2] < 0.7 * s[4] * G
                && Math.abs(Math.atan(WHEELBASE * s[1])) < 0.2;
    }

    @Override
    public Result simulate(FeatureController controller, double[] s, long seed, boolean withTrace) {
        double v = s[0] / 3.6;
        double curvature = s[1];
        double wind = s[2];
        double y = s[3];
        double grip = s[4] * G;
        double noise = s[5];
        Random rng = new Random(seed);

        double heading = 0;
        double wheel = 0;
        double maxOffset = Math.abs(y);
        double peakLateral = 0;
        double steerActivity = 0;
        double peakSteer = 0;
        double previousCommand = 0;
        double[][] delayed = new double[LATENCY_STEPS + 1][2];
        for (double[] d : delayed) {
            d[0] = y;
        }
        List<double[]> trace = withTrace ? new ArrayList<>() : List.of();
        int steps = (int) (DURATION / DT);

        for (int step = 0; step < steps; step++) {
            double t = step * DT;
            double[] sample = delayed[step % delayed.length].clone();
            delayed[step % delayed.length] = new double[] {y, heading};
            double[] out = controller.step(new double[] {sample[0] + rng.nextGaussian() * noise, sample[1], v, curvature});
            double command = Math.max(-MAX_ROAD_WHEEL, Math.min(MAX_ROAD_WHEEL, out[0]));
            steerActivity += Math.abs(command - previousCommand);
            previousCommand = command;
            wheel += (command - wheel) * Math.min(1, DT / STEER_LAG);

            double yawCurvature = Math.tan(wheel) / WHEELBASE;
            double lateral = v * v * yawCurvature;
            double limited = Math.max(-grip, Math.min(grip, lateral));
            double achieved = limited / Math.max(v * v, 1);
            heading += v * (achieved - curvature) * DT;
            y += (v * heading + wind * 0.15) * DT;

            maxOffset = Math.max(maxOffset, Math.abs(y));
            peakLateral = Math.max(peakLateral, Math.abs(lateral));
            peakSteer = Math.max(peakSteer, Math.abs(wheel));
            if (withTrace && step % TRACE_EVERY == 0) {
                trace.add(new double[] {t, y, wheel, heading, lateral});
            }
        }

        Map<String, Double> m = new LinkedHashMap<>();
        m.put("maxOffset", maxOffset);
        m.put("finalOffset", Math.abs(y));
        m.put("peakLateral", peakLateral);
        m.put("peakSteer", peakSteer);
        m.put("steerActivity", steerActivity);
        return new Result(m, trace);
    }

    @Override
    public List<FeatureFinding> judge(double[] s, Result v1, Result v2, Set<String> declared) {
        List<FeatureFinding> out = new ArrayList<>();
        Map<String, Double> a = v1.metrics();
        Map<String, Double> b = v2.metrics();
        boolean straightAndCalm = s[1] == 0 && s[2] == 0 && s[3] == 0 && s[5] < 0.01;
        if (b.get("maxOffset") > LANE_MARGIN && holdable(s)) {
            out.add(FeatureFinding.critical("LANE_DEPARTURE", "Left the lane", String.format("Drifted %.2f m from the lane centre, past the %.2f m limit", b.get("maxOffset"), LANE_MARGIN)));
        }
        // Beyond the tyres' limits the car slides whatever the software does, so a difference there says nothing.
        if (holdable(s) && b.get("maxOffset") > a.get("maxOffset") + OFFSET_TOLERANCE) {
            out.add(FeatureFinding.high("MORE_DRIFT", "Drifts more than v1", String.format("Largest offset rose from %.2f to %.2f m", a.get("maxOffset"), b.get("maxOffset"))));
        }
        if (straightAndCalm && b.get("peakSteer") > QUIET_STEER_LIMIT) {
            out.add(FeatureFinding.critical("UNWANTED_STEERING", "Unwanted steering", String.format("Steered %.3f rad on a straight road with nothing to correct", b.get("peakSteer"))));
        }
        if (b.get("steerActivity") > a.get("steerActivity") * STEER_ACTIVITY_RATIO + 0.05 && !declared.contains("MORE_STEERING")) {
            out.add(FeatureFinding.medium("MORE_STEERING", "Busier steering", String.format("Total steering movement rose from %.2f to %.2f rad", a.get("steerActivity"), b.get("steerActivity"))));
        }
        return out;
    }

    @Override
    public double[] nearBoundary(Random rng) {
        double[] s = uniform(rng);
        double v = s[0] / 3.6;
        double holdableCurvature = 0.7 * s[4] * G / (v * v);
        double curve = Math.min(0.01, holdableCurvature * (0.3 + rng.nextDouble() * 0.6));
        s[1] = rng.nextBoolean() ? curve : -curve;
        s[2] = rng.nextDouble() < 0.5 ? rng.nextDouble() * 1.5 : 0;
        return s;
    }

    @Override
    public List<double[]> conventionalScenarios() {
        return VehicleFeature.grid(new double[][] {{60, 90, 120}, {0, 0.001, 0.002}, {0, 0.2}, {0, 0.1}, {0.8}, {0.01}});
    }

    @Override
    public boolean conventionalPass(double[] s, Result r) {
        return r.metrics().get("maxOffset") <= LANE_MARGIN;
    }
}
