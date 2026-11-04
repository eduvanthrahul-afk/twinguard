package com.twinguard.feature;

import com.twinguard.feature.logic.PedestrianLogic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Emergency stop for a pedestrian who may step into the car's path. Detection is simulated from
 * range, field of view, noise, and dropouts. No camera images or real perception are involved.
 */
public final class PedestrianFeature implements VehicleFeature {

    private static final double DT = 0.02;
    private static final double DURATION = 14;
    private static final double G = 9.81;
    private static final double SENSOR_RANGE = 70;
    private static final double BRAKE_LAG = 0.12;
    private static final double MAX_DECEL = 10.5;
    private static final double BODY_HALF_WIDTH = 1.1;
    private static final double LATE_TOLERANCE = 0.2;
    private static final double IMPACT_TOLERANCE = 3 / 3.6;
    private static final int TRACE_EVERY = 5;

    @Override
    public String id() {
        return "pedestrian";
    }

    @Override
    public String name() {
        return "Pedestrian emergency stop";
    }

    @Override
    public String summary() {
        return "Stops for a person stepping into the road, and leaves people on the pavement alone.";
    }

    @Override
    public String limitations() {
        return "Detection is simulated from range, noise, and dropouts, with no camera images or real perception. So this tests the decision and braking logic, not whether a person would be seen. It covers one pedestrian walking straight at a constant speed.";
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param("speedKph", "Speed", "km/h", 20, 70, 40),
                new Param("distance", "Distance when first visible", "m", 15, 70, 60),
                new Param("walkSpeed", "Walking speed", "m/s", 0.5, 3, 1.4),
                new Param("startSide", "Starting distance from path", "m", 1.5, 6, 3),
                new Param("crossing", "Walks into the road (above 0.5)", "", 0, 1, 1),
                new Param("friction", "Road friction", "", 0.3, 1.0, 0.8),
                new Param("dropout", "Detection dropout rate", "", 0, 0.25, 0));
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
                new Channel("gap", "Distance to pedestrian", "m"),
                new Channel("speed", "Vehicle speed", "km/h"),
                new Channel("brake", "Brake request", ""),
                new Channel("lateral", "Pedestrian's distance from path", "m"));
    }

    @Override
    public double stepSeconds() {
        return DT;
    }

    @Override
    public List<String> inputNames() {
        return List.of("detected", "distance", "lateral", "lateral_speed", "speed", "friction");
    }

    @Override
    public List<String> outputNames() {
        return List.of("brake_command");
    }

    @Override
    public Map<String, Double> defaultSettings() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("confirmSamples", 5.0);
        s.put("pathHalfWidth", 1.4);
        s.put("stopMargin", 1.2);
        s.put("stopBuffer", 3.0);
        s.put("predictionMargin", 1.5);
        s.put("brakeLevel", 1.0);
        s.put("dropoutSamples", 10.0);
        return s;
    }

    @Override
    public FeatureController logic(Map<String, Double> settings) {
        return new PedestrianLogic(settings);
    }

    @Override
    public Class<? extends FeatureController> logicClass() {
        return PedestrianLogic.class;
    }

    @Override
    public List<SettingFault> faults() {
        List<SettingFault> f = new ArrayList<>();
        for (double v : new double[] {1.0, 2.0, 15.0, 30.0, 60.0}) {
            f.add(new SettingFault("confirm-" + v, "Detection", "Confirmation set to " + (int) v + " samples", Map.of("confirmSamples", v)));
        }
        for (double v : new double[] {0.0, 2.0, 5.0}) {
            f.add(new SettingFault("dropout-" + v, "Detection", "Tolerates " + (int) v + " missed samples", Map.of("dropoutSamples", v)));
        }
        for (double v : new double[] {0.5, 0.9, 1.3, 3.0, 8.0}) {
            f.add(new SettingFault("path-" + v, "Path prediction", "Path half-width set to " + v + " m", Map.of("pathHalfWidth", v)));
        }
        for (double v : new double[] {-20.0, 0.0, 0.5, 5.0}) {
            f.add(new SettingFault("prediction-" + v, "Path prediction", "Prediction margin set to " + v + " s", Map.of("predictionMargin", v)));
        }
        for (double v : new double[] {0.6, 0.8, 1.0, 1.1, 2.5}) {
            f.add(new SettingFault("stop-margin-" + v, "Braking", "Stopping margin set to ×" + v, Map.of("stopMargin", v)));
        }
        for (double v : new double[] {0.0, 1.0, 15.0}) {
            f.add(new SettingFault("buffer-" + v, "Braking", "Stopping buffer set to " + v + " m", Map.of("stopBuffer", v)));
        }
        for (double v : new double[] {0.3, 0.6, 0.85}) {
            f.add(new SettingFault("brake-" + v, "Braking", "Brake force set to ×" + v, Map.of("brakeLevel", v)));
        }
        return f;
    }

    @Override
    public List<Release> releases() {
        return List.of(
                new Release("ped-4.1-fewer-stops", "Pedestrian 4.1 Fewer False Stops", "Requires longer confirmation, to reduce false stops.",
                        Map.of("confirmSamples", 40.0), Set.of(), "No behaviour change declared"),
                new Release("ped-4.2-smooth", "Pedestrian 4.2 Earlier Braking", "Starts braking slightly earlier for a smoother stop. This change is declared.",
                        Map.of("stopMargin", 1.3), Set.of("EARLY_BRAKING"), "Braking may begin slightly earlier"),
                new Release("ped-4.3-kerb", "Pedestrian 4.3 Kerb Awareness", "Widens the path it watches, so it reacts sooner to people near the kerb.",
                        Map.of("pathHalfWidth", 7.0), Set.of(), "No behaviour change declared"));
    }

    /** True when the person is clearly seen early enough, with room to stop. */
    static boolean avoidable(double[] s) {
        if (s[4] <= 0.5) {
            return false;
        }
        double v = s[0] / 3.6;
        double needed = (v * 0.5 + v * v / (2 * s[5] * G)) * 1.25 + 4;
        return s[1] >= needed + v * s[6] * 2;
    }

    @Override
    public Result simulate(FeatureController controller, double[] s, long seed, boolean withTrace) {
        double speed = s[0] / 3.6;
        double gap = s[1];
        double walk = s[2];
        double side = s[3];
        boolean crossing = s[4] > 0.5;
        double friction = s[5];
        double dropout = s[6];
        Random rng = new Random(seed);

        double decel = 0;
        double onset = -1;
        double peakBrake = 0;
        double minGap = gap;
        boolean hit = false;
        double impact = 0;
        List<double[]> trace = withTrace ? new ArrayList<>() : List.of();
        int steps = (int) (DURATION / DT);

        for (int step = 0; step < steps; step++) {
            double t = step * DT;
            double lateralSpeed = crossing ? -walk : 0;
            boolean visible = gap > 0 && gap <= SENSOR_RANGE && rng.nextDouble() >= dropout;
            double[] out = controller.step(new double[] {
                    visible ? 1 : 0,
                    visible ? gap + rng.nextGaussian() * 0.15 : 0,
                    visible ? side + rng.nextGaussian() * 0.05 : 0,
                    visible ? lateralSpeed + rng.nextGaussian() * 0.05 : 0,
                    speed,
                    friction});
            double brake = Math.max(0, Math.min(1, out[0]));
            if (brake > 0 && onset < 0) {
                onset = t;
            }
            peakBrake = Math.max(peakBrake, brake);

            double target = Math.min(brake * MAX_DECEL, friction * G);
            decel += (target - decel) * Math.min(1, DT / BRAKE_LAG);
            speed = Math.max(0, speed - decel * DT);
            gap -= speed * DT;
            if (crossing) {
                side -= walk * DT;
            }
            minGap = Math.min(minGap, Math.max(gap, 0));

            if (withTrace && step % TRACE_EVERY == 0) {
                trace.add(new double[] {t, gap, speed * 3.6, brake, side});
            }
            boolean besideCar = gap <= 0 && gap > -4.5;
            if (besideCar && Math.abs(side) < BODY_HALF_WIDTH && speed > 0) {
                hit = true;
                impact = speed;
                break;
            }
            if (speed <= 0 && brake > 0) {
                break;
            }
            if (gap < -5) {
                break;
            }
        }

        Map<String, Double> m = new LinkedHashMap<>();
        m.put("hit", hit ? 1.0 : 0.0);
        m.put("impact", impact);
        m.put("braked", onset >= 0 ? 1.0 : 0.0);
        m.put("onset", onset);
        m.put("minGap", minGap);
        m.put("peakBrake", peakBrake);
        return new Result(m, trace);
    }

    @Override
    public List<FeatureFinding> judge(double[] s, Result v1, Result v2, Set<String> declared) {
        List<FeatureFinding> out = new ArrayList<>();
        Map<String, Double> a = v1.metrics();
        Map<String, Double> b = v2.metrics();
        boolean hit = b.get("hit") > 0;
        boolean v1Hit = a.get("hit") > 0;
        boolean walkingAlongside = s[4] <= 0.5;
        if (hit && avoidable(s)) {
            out.add(FeatureFinding.critical("AVOIDABLE_HIT", "Avoidable pedestrian hit", String.format("Hit the pedestrian at %.0f km/h, although there was room to stop", b.get("impact") * 3.6)));
        }
        if (hit && !v1Hit) {
            out.add(FeatureFinding.critical("NEW_HIT", "New pedestrian hit", String.format("Hit the pedestrian at %.0f km/h, while v1 didn't", b.get("impact") * 3.6)));
        }
        if (hit && v1Hit && b.get("impact") > a.get("impact") + IMPACT_TOLERANCE) {
            out.add(FeatureFinding.critical("WORSE_IMPACT", "Harder impact", String.format("Impact speed rose from %.0f to %.0f km/h", a.get("impact") * 3.6, b.get("impact") * 3.6)));
        }
        if (walkingAlongside && b.get("braked") > 0 && a.get("braked") == 0) {
            out.add(FeatureFinding.critical("PHANTOM_STOP", "False stop", String.format("Braked hard for a person %.1f m from the path, who never stepped into the road", s[3])));
        }
        if (a.get("braked") > 0 && b.get("braked") > 0) {
            double shift = b.get("onset") - a.get("onset");
            if (shift > LATE_TOLERANCE) {
                out.add(FeatureFinding.high("LATE_BRAKING", "Late braking", String.format("Braking began %.0f ms later than v1", shift * 1000)));
            }
            if (-shift > LATE_TOLERANCE && !declared.contains("EARLY_BRAKING")) {
                out.add(FeatureFinding.medium("EARLY_BRAKING", "Early braking", String.format("Braking began %.0f ms earlier than v1", -shift * 1000)));
            }
        }
        if (a.get("braked") > 0 && b.get("braked") == 0 && !walkingAlongside) {
            out.add(FeatureFinding.critical("MISSED_BRAKING", "Missed braking", "Never braked, while v1 did"));
        }
        if (a.get("braked") > 0 && b.get("peakBrake") < a.get("peakBrake") * 0.9 && b.get("braked") > 0) {
            out.add(FeatureFinding.high("WEAK_BRAKING", "Weak braking", String.format("Peak brake request fell from %.0f%% to %.0f%%", a.get("peakBrake") * 100, b.get("peakBrake") * 100)));
        }
        return out;
    }

    @Override
    public double[] nearBoundary(Random rng) {
        double[] s = uniform(rng);
        double v = s[0] / 3.6;
        double stop = v * 0.5 + v * v / (2 * s[5] * G);
        s[1] = stop * (1 + rng.nextDouble()) + 3;
        s[4] = rng.nextDouble() < 0.8 ? 1 : 0;
        s[2] = (s[3] / Math.max(s[1] / v, 0.5)) * (0.6 + rng.nextDouble() * 0.8);
        return s;
    }

    @Override
    public List<double[]> conventionalScenarios() {
        return VehicleFeature.grid(new double[][] {{30, 40, 50}, {60, 70}, {1.4}, {1.5, 2.0}, {1}, {0.8}, {0}});
    }

    @Override
    public boolean conventionalPass(double[] s, Result r) {
        return r.metrics().get("hit") == 0;
    }
}
