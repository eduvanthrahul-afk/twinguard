package com.twinguard.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.twinguard.calibration.Calibration;
import com.twinguard.feature.logic.CruiseLogic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** Adaptive cruise control, following a lead car that may brake hard. */
public final class CruiseFeature implements VehicleFeature {

    private static final double DT = 0.05;
    private static final double DURATION = 35;
    private static final double G = 9.81;
    private static final double LEAD_BRAKE_AT = 3;
    private static final double SPEEDUP_TOLERANCE = 1.0;
    private static final double IMPACT_TOLERANCE = 2.0 / 3.6;
    /** Fitted to OpenACC recordings when calibration/cruise.json is present. */
    private static final Optional<JsonNode> CAL = Calibration.load("cruise");
    private static final double GAP_TOLERANCE = Calibration.value(CAL, "tolerances", "gapM", 2.0);
    private static final double DECEL_TOLERANCE = Calibration.value(CAL, "tolerances", "decel", 1.5);
    /** The powertrain and brakes: how quickly the car's acceleration follows a request. */
    public static final double PLANT_LAG_S = Calibration.value(CAL, "plant", "lagS", 0.15);
    public static final double PLANT_MAX_ACCEL = 3.0;
    private static final double LOW_GAP = 15;
    private static final int TRACE_EVERY = 4;

    @Override
    public String id() {
        return "cruise";
    }

    @Override
    public String name() {
        return "Adaptive cruise control";
    }

    @Override
    public String summary() {
        return "Holds a set speed and a safe following distance behind a lead car.";
    }

    @Override
    public String limitations() {
        return "One lead car in a straight lane, with simple longitudinal physics. It doesn't cover cut-ins, curves, hills, or radar tracking errors beyond noise. "
                + Calibration.summary(CAL);
    }

    /** One plant step: the car's acceleration moves toward the request, within its power and grip. */
    public static double plantStep(double accel, double request, double friction, double lagS, double dt) {
        double target = Math.max(-friction * G, Math.min(PLANT_MAX_ACCEL, request));
        return accel + (target - accel) * Math.min(1, dt / lagS);
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param("egoKph", "Set speed", "km/h", 40, 130, 90),
                new Param("gap", "Initial gap", "m", 15, 120, 80),
                new Param("leadKph", "Lead car speed", "km/h", 20, 130, 90),
                new Param("leadBrake", "Lead car braking", "m/s²", 0, 8, 0),
                new Param("friction", "Road friction", "", 0.3, 1.0, 0.8),
                new Param("noise", "Radar noise", "m", 0, 1, 0));
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
                new Channel("gap", "Gap to lead car", "m"),
                new Channel("speed", "Vehicle speed", "km/h"),
                new Channel("lead", "Lead car speed", "km/h"),
                new Channel("accel", "Acceleration", "m/s²"));
    }

    @Override
    public double stepSeconds() {
        return DT;
    }

    @Override
    public List<String> inputNames() {
        return List.of("gap", "ego_speed", "lead_speed", "set_speed");
    }

    @Override
    public List<String> outputNames() {
        return List.of("accel_command");
    }

    @Override
    public Map<String, Double> defaultSettings() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("headway", Calibration.value(CAL, "settings", "headway", 1.8));
        s.put("standstillGap", Calibration.value(CAL, "settings", "standstillGap", 6.0));
        s.put("gapGain", Calibration.value(CAL, "settings", "gapGain", 0.25));
        s.put("speedGain", Calibration.value(CAL, "settings", "speedGain", 0.5));
        s.put("maxAccel", Calibration.value(CAL, "settings", "maxAccel", 1.5));
        s.put("brakeMargin", 1.3);
        s.put("filter", Calibration.value(CAL, "settings", "filter", 0.3));
        s.put("anticipation", 1.0);
        return s;
    }

    @Override
    public FeatureController logic(Map<String, Double> settings) {
        return new CruiseLogic(settings);
    }

    @Override
    public Class<? extends FeatureController> logicClass() {
        return CruiseLogic.class;
    }

    @Override
    public List<SettingFault> faults() {
        List<SettingFault> f = new ArrayList<>();
        for (double v : new double[] {0.4, 0.8, 1.2, 1.6, 3.0}) {
            f.add(new SettingFault("headway-" + v, "Following distance", "Headway set to " + v + " s", Map.of("headway", v)));
        }
        for (double v : new double[] {1.0, 3.0, 15.0}) {
            f.add(new SettingFault("standstill-" + v, "Following distance", "Standstill gap set to " + v + " m", Map.of("standstillGap", v)));
        }
        for (double v : new double[] {0.02, 0.1, 1.0}) {
            f.add(new SettingFault("gap-gain-" + v, "Control gains", "Gap gain set to " + v, Map.of("gapGain", v)));
        }
        for (double v : new double[] {0.1, 0.25, 1.5}) {
            f.add(new SettingFault("speed-gain-" + v, "Control gains", "Speed gain set to " + v, Map.of("speedGain", v)));
        }
        for (double v : new double[] {0.5, 0.75, 1.0, 1.1}) {
            f.add(new SettingFault("brake-margin-" + v, "Emergency response", "Braking margin set to ×" + v, Map.of("brakeMargin", v)));
        }
        for (double v : new double[] {0.0, 0.5, 0.9}) {
            f.add(new SettingFault("anticipation-" + v, "Emergency response", "Lead-car braking anticipation set to ×" + v, Map.of("anticipation", v)));
        }
        for (double v : new double[] {3.0, 6.0}) {
            f.add(new SettingFault("max-accel-" + v, "Limits", "Maximum acceleration raised to " + v + " m/s²", Map.of("maxAccel", v)));
        }
        for (double v : new double[] {0.02, 0.05, 1.0}) {
            f.add(new SettingFault("filter-" + v, "Sensor processing", "Radar filter set to " + v, Map.of("filter", v)));
        }
        return f;
    }

    @Override
    public Map<String, String> declarableChanges() {
        return Map.of("SLOWER_RECOVERY", "Acceleration back to speed may be gentler than v1");
    }

    @Override
    public List<Release> releases() {
        // Each release is defined relative to v1, so it keeps its meaning when v1 is recalibrated.
        Map<String, Double> v1 = defaultSettings();
        return List.of(
                new Release("acc-2.1-traffic", "ACC 2.1 Traffic Flow", "Follows closer, to stop other cars cutting in.",
                        Map.of("headway", round2(v1.get("headway") * 0.4)), Set.of(), "No behaviour change declared"),
                new Release("acc-2.2-comfort", "ACC 2.2 Smoother Starts", "Accelerates a little more gently. This change is declared.",
                        Map.of("maxAccel", round2(v1.get("maxAccel") * 0.85)), Set.of("SLOWER_RECOVERY"), "Acceleration may be gentler"),
                new Release("acc-2.3-smooth-radar", "ACC 2.3 Smoother Radar", "Filters radar more heavily, for a calmer ride.",
                        Map.of("filter", v1.get("filter") / 20), Set.of(), "No behaviour change declared"));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** True when an attentive system clearly had room to avoid the lead car. */
    static boolean avoidable(double[] s) {
        double ego = s[0] / 3.6;
        double lead = Math.min(s[2] / 3.6, 200);
        double leadBrake = Math.min(s[3], s[4] * G);
        double available = 0.9 * s[4] * G;
        double gap = s[1];
        // Closing fast on a slower car has to be survivable, however gently that car is braking.
        if (lead < ego) {
            double closingNow = ego - lead;
            if (gap < (closingNow * 0.6 + closingNow * closingNow / (2 * available)) * 1.25 + 4) {
                return false;
            }
        }
        if (leadBrake > 0.1) {
            double leadStop = lead * lead / (2 * leadBrake);
            // The lead car drives on before it starts braking.
            double egoStop = ego * 0.6 + ego * ego / (2 * available);
            return gap + lead * LEAD_BRAKE_AT + leadStop >= (egoStop + ego * LEAD_BRAKE_AT) * 1.25 + 4;
        }
        if (lead >= ego) {
            return true;
        }
        double closing = ego - lead;
        return gap >= (closing * 0.6 + closing * closing / (2 * available)) * 1.25 + 4;
    }

    @Override
    public Result simulate(FeatureController controller, double[] s, long seed, boolean withTrace) {
        double set = s[0] / 3.6;
        double ego = set;
        double lead = s[2] / 3.6;
        double gap = s[1];
        double leadBrake = Math.min(s[3], s[4] * G);
        double friction = s[4];
        double noise = s[5];
        Random rng = new Random(seed);

        double minGap = gap;
        double peakDecel = 0;
        double peakSpeed = ego;
        double accel = 0;
        boolean collided = false;
        double impact = 0;
        double travelled = 0;
        List<double[]> trace = withTrace ? new ArrayList<>() : List.of();
        int steps = (int) (DURATION / DT);

        for (int step = 0; step < steps; step++) {
            double t = step * DT;
            double[] out = controller.step(new double[] {gap + rng.nextGaussian() * noise, ego, lead, set});
            double request = out[0];
            accel = plantStep(accel, request, friction, PLANT_LAG_S, DT);
            ego = Math.max(0, ego + accel * DT);
            if (t >= LEAD_BRAKE_AT) {
                lead = Math.max(0, lead - leadBrake * DT);
            }
            gap += (lead - ego) * DT;
            travelled += ego * DT;

            minGap = Math.min(minGap, gap);
            peakDecel = Math.max(peakDecel, -accel);
            peakSpeed = Math.max(peakSpeed, ego);
            if (withTrace && step % TRACE_EVERY == 0) {
                trace.add(new double[] {t, gap, ego * 3.6, lead * 3.6, accel});
            }
            if (gap <= 0) {
                collided = true;
                impact = (ego - lead);
                minGap = 0;
                break;
            }
        }

        Map<String, Double> m = new LinkedHashMap<>();
        m.put("minGap", minGap);
        m.put("collision", collided ? 1.0 : 0.0);
        m.put("impact", impact);
        m.put("peakDecel", peakDecel);
        m.put("overspeed", peakSpeed - set);
        m.put("distance", travelled);
        return new Result(m, trace);
    }

    @Override
    public List<FeatureFinding> judge(double[] s, Result v1, Result v2, Set<String> declared) {
        List<FeatureFinding> out = new ArrayList<>();
        Map<String, Double> a = v1.metrics();
        Map<String, Double> b = v2.metrics();
        boolean hit = b.get("collision") > 0;
        boolean v1Hit = a.get("collision") > 0;
        if (hit && avoidable(s)) {
            out.add(FeatureFinding.critical("AVOIDABLE_COLLISION", "Avoidable collision", String.format("Hit the lead car at a closing speed of %.0f km/h, although there was room to stop", b.get("impact") * 3.6)));
        }
        if (hit && !v1Hit) {
            out.add(FeatureFinding.critical("NEW_COLLISION", "New collision", String.format("Hit the lead car, while v1 kept a %.1f m gap", a.get("minGap"))));
        }
        if (hit && v1Hit && b.get("impact") > a.get("impact") + IMPACT_TOLERANCE) {
            out.add(FeatureFinding.critical("WORSE_IMPACT", "Harder impact", String.format("Closing speed at impact rose from %.0f to %.0f km/h", a.get("impact") * 3.6, b.get("impact") * 3.6)));
        }
        if (b.get("overspeed") > SPEEDUP_TOLERANCE) {
            out.add(FeatureFinding.critical("OVERSPEED", "Exceeded the set speed", String.format("Went %.1f km/h over the set speed", b.get("overspeed") * 3.6)));
        }
        if (!hit && !v1Hit && b.get("minGap") < LOW_GAP && b.get("minGap") < a.get("minGap") - GAP_TOLERANCE) {
            out.add(FeatureFinding.high("REDUCED_GAP", "Followed too closely", String.format("Smallest gap fell from %.1f to %.1f m", a.get("minGap"), b.get("minGap"))));
        }
        if (b.get("peakDecel") > a.get("peakDecel") + DECEL_TOLERANCE) {
            out.add(FeatureFinding.medium("HARSHER_BRAKING", "Harsher braking", String.format("Peak braking rose from %.1f to %.1f m/s²", a.get("peakDecel"), b.get("peakDecel"))));
        }
        if (b.get("distance") < a.get("distance") * 0.93 && !hit && !v1Hit && !declared.contains("SLOWER_RECOVERY")) {
            out.add(FeatureFinding.medium("SLOWER_RECOVERY", "Unnecessarily slow", String.format("Travelled %.0f m, while v1 travelled %.0f m in the same time", b.get("distance"), a.get("distance"))));
        }
        return out;
    }

    @Override
    public double[] nearBoundary(Random rng) {
        double[] s = uniform(rng);
        double ego = s[0] / 3.6;
        s[2] = Math.min(s[0], 20 + rng.nextDouble() * 110);
        s[3] = rng.nextDouble() < 0.7 ? 2 + rng.nextDouble() * 6 : 0;
        double stop = ego * ego / (2 * s[4] * G);
        s[1] = stop * (0.3 + rng.nextDouble()) + ego * rng.nextDouble();
        return s;
    }

    @Override
    public List<double[]> conventionalScenarios() {
        return VehicleFeature.grid(new double[][] {{60, 90, 110}, {60, 90}, {60, 90}, {0, 1.5}, {0.8}, {0.1}});
    }

    @Override
    public boolean conventionalPass(double[] s, Result r) {
        return r.metrics().get("collision") == 0 && r.metrics().get("overspeed") <= SPEEDUP_TOLERANCE;
    }
}
