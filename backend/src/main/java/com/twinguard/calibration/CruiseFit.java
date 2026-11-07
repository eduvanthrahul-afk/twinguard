package com.twinguard.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.twinguard.feature.CruiseFeature;
import com.twinguard.feature.logic.CruiseLogic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Fits the adaptive cruise control model to OpenACC recordings.
 *
 * Each recording is cut into 30 s windows. In each window the real leader's speed is replayed, and the
 * simulated follower (the real {@link CruiseLogic} plus the cruise plant) starts from the real follower's
 * speed and gap. The fit adjusts the v1 settings and the plant lag until the simulated gap and speed track
 * the recorded ones. Two recordings are held out and never used for fitting.
 */
public final class CruiseFit {

    private static final double DATA_DT = 0.1;
    private static final double SIM_DT = 0.05;
    private static final int WINDOW = 300;
    private static final double DRY_TRACK_FRICTION = 0.9;
    private static final double SPEED_WEIGHT = 4.0;
    private static final Set<String> HELD_OUT = Set.of("ASta_040719_platoon8.csv", "ASta_050719_platoon2.csv");

    /** The fitted values, with log-space bounds. Headway is fitted separately for each driver setting. */
    private static final String[] KEYS = {"headwayMin", "headwayMax", "standstillGap", "gapGain", "speedGain", "maxAccel", "lagS", "filter"};
    private static final double[] LO = {0.4, 0.8, 1.0, 0.01, 0.02, 0.3, 0.1, 0.005};
    /** The plant lag is capped at 0.6 s: brakes and powertrain respond within tenths of a second, so slower
     *  responses in the recordings belong to each car's ACC software, and the fit has to explain them there. */
    private static final double[] HI = {3.0, 4.0, 15.0, 2.0, 3.0, 3.0, 0.6, 1.0};

    record Window(OpenAccData.Pair pair, int start, double setSpeed, boolean test) {
    }

    record Error(double gapRms, double speedRms, int windows) {
        double objective() {
            return gapRms + SPEED_WEIGHT * speedRms;
        }
    }

    private final List<Window> windows = new ArrayList<>();

    private CruiseFit(List<OpenAccData.Pair> pairs) {
        for (OpenAccData.Pair p : pairs) {
            double maxSpeed = Arrays.stream(p.egoSpeed()).max().orElse(0);
            boolean maxSetting = "max".equals(p.distanceSetting());
            int index = 0;
            for (int s = 0; s + WINDOW <= p.size(); s += WINDOW, index++) {
                // Too slow to tell anything about following behaviour.
                if (mean(p.egoSpeed(), s, WINDOW) < 3) {
                    continue;
                }
                boolean test = HELD_OUT.contains(p.file()) || (maxSetting && index % 2 == 1);
                windows.add(new Window(p, s, maxSpeed + 3, test));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        Path data = Path.of(args.length > 0 ? args[0] : "../data/public/openacc_astazero");
        Path out = Path.of(args.length > 1 ? args[1] : "src/main/resources/calibration/cruise.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, run(data));
        System.out.println("Wrote " + out.toAbsolutePath());
    }

    public static String run(Path data) throws IOException {
        List<OpenAccData.Pair> pairs = OpenAccData.load(data);
        CruiseFit fit = new CruiseFit(pairs);
        double[] design = {1.8, 1.8, 6.0, 0.25, 0.5, 1.5, 0.15, 0.3};
        // Measured directly, so it is set from the data rather than fitted: the 99th percentile of the
        // followers' recorded acceleration.
        double measuredMaxAccel = fit.percentileAccel(99);
        double[] start = design.clone();
        start[5] = measuredMaxAccel;
        double[] best = fit.optimise(start, 5);

        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode root = json.createObjectNode();
        Error before = fit.error(design, true);
        Error after = fit.error(best, true);
        Error trainAfter = fit.error(best, false);

        double gapNoise = fit.gapNoise();
        double accelNoise = fit.accelNoise();

        ObjectNode settings = root.putObject("settings");
        // v1 uses the longest following-distance setting: the more conservative of the two that were recorded.
        settings.put("headway", round(best[1], 3));
        settings.put("standstillGap", round(best[2], 3));
        settings.put("gapGain", round(best[3], 4));
        settings.put("speedGain", round(best[4], 4));
        settings.put("maxAccel", round(best[5], 3));
        settings.put("filter", round(best[7], 4));
        ObjectNode headways = root.putObject("headwayBySetting");
        headways.put("min", round(best[0], 3));
        headways.put("max", round(best[1], 3));
        root.putObject("plant").put("lagS", round(best[6], 3));

        ObjectNode tol = root.putObject("tolerances");
        tol.put("gapM", round(Math.max(2.0, 3 * gapNoise), 2));
        tol.put("decel", round(Math.max(1.5, 3 * accelNoise), 2));

        ObjectNode fitNode = root.putObject("fit");
        fitNode.put("windows", fit.windows.size());
        fitNode.put("heldOutWindows", after.windows());
        fitNode.put("windowSeconds", WINDOW * DATA_DT);
        fitNode.set("heldOutBefore", errorNode(json, before));
        fitNode.set("heldOutAfter", errorNode(json, after));
        fitNode.set("trainAfter", errorNode(json, trainAfter));
        ObjectNode perVehicle = fitNode.putObject("heldOutByFollower");
        fit.byFollower(best).forEach((k, v) -> perVehicle.set(k, errorNode(json, v)));

        ObjectNode observed = root.putObject("observed");
        observed.put("gapNoiseM", round(gapNoise, 3));
        observed.put("accelNoise", round(accelNoise, 3));
        double[][] extremes = fit.extremesByPosition();
        ArrayNode decel = observed.putArray("peakDecelByPlatoonPosition");
        for (double[] e : extremes) {
            decel.add(round(e[0], 2));
        }
        observed.put("p99Accel", round(fit.percentileAccel(99), 2));
        observed.put("p1Accel", round(fit.percentileAccel(1), 2));

        ObjectNode source = root.putObject("source");
        source.put("dataset", "OpenACC, AstaZero campaign (July 2019)");
        source.put("publisher", "European Commission, Joint Research Centre");
        source.put("licence", "CC BY 4.0");
        source.put("url", "https://jeodpp.jrc.ec.europa.eu/ftp/jrc-opendata/TransportExpData/JRCDBT0001/LATEST/AstaZero/");
        source.put("citation", "Makridis et al. (2021), OpenACC. An open database of car-following experiments to study the properties of commercial ACC systems, Transportation Research Part C 125.");
        source.put("vehicles", "Audi A8, Tesla Model 3, BMW X5, Audi A6, Mercedes A-Class, all on ACC");
        source.put("pairs", pairs.size());

        root.put("summary", String.format(
                "Calibrated against OpenACC (EU JRC, CC BY 4.0): five production cars on ACC at AstaZero. On held-out recordings the simulated gap is within %.1f m RMS and speed within %.1f km/h RMS (design values: %.1f m, %.1f km/h). Emergency braking beyond ACC levels is not in this data.",
                after.gapRms(), after.speedRms() * 3.6, before.gapRms(), before.speedRms() * 3.6));
        return json.writeValueAsString(root);
    }

    private static ObjectNode errorNode(ObjectMapper json, Error e) {
        ObjectNode n = json.createObjectNode();
        n.put("gapRmsM", round(e.gapRms(), 2));
        n.put("speedRmsKph", round(e.speedRms() * 3.6, 2));
        n.put("windows", e.windows());
        return n;
    }

    /** Pattern search in log space: try each value a step up and down, and shrink the step when nothing helps. */
    double[] optimise(double[] x, int fixedIndex) {
        double best = error(x, false).objective();
        double step = 0.4;
        while (step > 0.01) {
            boolean improved = false;
            for (int i = 0; i < x.length; i++) {
                if (i == fixedIndex) {
                    continue;
                }
                for (double dir : new double[] {1, -1}) {
                    double[] y = x.clone();
                    y[i] = Math.max(LO[i], Math.min(HI[i], x[i] * Math.exp(dir * step)));
                    double e = error(y, false).objective();
                    if (e < best - 1e-6) {
                        best = e;
                        x = y;
                        improved = true;
                    }
                }
            }
            if (!improved) {
                step /= 2;
            }
            System.out.printf("step %.3f  objective %.3f  %s%n", step, best, describe(x));
        }
        return x;
    }

    private static String describe(double[] x) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < x.length; i++) {
            b.append(KEYS[i]).append('=').append(String.format("%.3f", x[i])).append(' ');
        }
        return b.toString();
    }

    Error error(double[] x, boolean test) {
        double gapSq = 0;
        double speedSq = 0;
        long n = 0;
        int count = 0;
        for (Window w : windows) {
            if (w.test() != test) {
                continue;
            }
            double[] e = simulate(w, x);
            gapSq += e[0];
            speedSq += e[1];
            n += (long) e[2];
            count++;
        }
        return new Error(Math.sqrt(gapSq / Math.max(1, n)), Math.sqrt(speedSq / Math.max(1, n)), count);
    }

    private Map<String, Error> byFollower(double[] x) {
        Map<String, double[]> acc = new TreeMap<>();
        for (Window w : windows) {
            if (!w.test()) {
                continue;
            }
            double[] e = simulate(w, x);
            double[] a = acc.computeIfAbsent(w.pair().follower(), k -> new double[4]);
            a[0] += e[0];
            a[1] += e[1];
            a[2] += e[2];
            a[3]++;
        }
        Map<String, Error> out = new LinkedHashMap<>();
        acc.forEach((k, a) -> out.put(k, new Error(Math.sqrt(a[0] / a[2]), Math.sqrt(a[1] / a[2]), (int) a[3])));
        return out;
    }

    /** Returns the summed squared gap error, summed squared speed error, and sample count for one window. */
    static double[] simulate(Window w, double[] x) {
        OpenAccData.Pair p = w.pair();
        boolean maxSetting = "max".equals(p.distanceSetting());
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("headway", maxSetting ? x[1] : x[0]);
        s.put("standstillGap", x[2]);
        s.put("gapGain", x[3]);
        s.put("speedGain", x[4]);
        s.put("maxAccel", x[5]);
        s.put("brakeMargin", 1.3);
        s.put("filter", x[7]);
        s.put("anticipation", 1.0);
        CruiseLogic logic = new CruiseLogic(s);
        double lag = x[6];

        int i0 = w.start();
        double ego = p.egoSpeed()[i0];
        double gap = p.gap()[i0];
        double accel = (p.egoSpeed()[i0 + 1] - ego) / DATA_DT;
        int sub = (int) Math.round(DATA_DT / SIM_DT);
        double gapSq = 0;
        double speedSq = 0;
        int n = 0;
        for (int i = i0; i < i0 + WINDOW - 1; i++) {
            for (int k = 0; k < sub; k++) {
                double f = (double) k / sub;
                double lead = p.leadSpeed()[i] * (1 - f) + p.leadSpeed()[i + 1] * f;
                double request = logic.step(new double[] {gap, ego, lead, w.setSpeed()})[0];
                accel = CruiseFeature.plantStep(accel, request, DRY_TRACK_FRICTION, lag, SIM_DT);
                ego = Math.max(0, ego + accel * SIM_DT);
                gap += (lead - ego) * SIM_DT;
            }
            double dg = gap - p.gap()[i + 1];
            double dv = ego - p.egoSpeed()[i + 1];
            gapSq += dg * dg;
            speedSq += dv * dv;
            n++;
        }
        return new double[] {gapSq, speedSq, n};
    }

    /** Measurement noise in the recorded gap: its scatter around a 1 s moving average. */
    private double gapNoise() {
        return scatter(true);
    }

    /** Noise in acceleration taken from the recorded speed. */
    private double accelNoise() {
        return scatter(false);
    }

    private double scatter(boolean gap) {
        double sq = 0;
        long n = 0;
        for (Window w : windows) {
            double[] v = gap ? w.pair().gap() : accel(w.pair().egoSpeed());
            for (int i = w.start() + 5; i < w.start() + WINDOW - 5; i++) {
                double avg = mean(v, i - 5, 11);
                sq += (v[i] - avg) * (v[i] - avg);
                n++;
            }
        }
        return Math.sqrt(sq / Math.max(1, n));
    }

    private double percentileAccel(double pct) {
        List<Double> all = new ArrayList<>();
        for (Window w : windows) {
            double[] a = smooth(accel(w.pair().egoSpeed()));
            for (int i = w.start(); i < w.start() + WINDOW; i++) {
                all.add(a[i]);
            }
        }
        all.sort(Double::compare);
        return all.get((int) Math.min(all.size() - 1, Math.round(pct / 100 * (all.size() - 1))));
    }

    /** The hardest smoothed braking seen at each position in the platoon, behind the leader. */
    private double[][] extremesByPosition() {
        double[][] out = new double[4][1];
        for (Window w : windows) {
            OpenAccData.Pair p = w.pair();
            int pos = p.position();
            double[] a = smooth(accel(p.egoSpeed()));
            for (int i = w.start(); i < w.start() + WINDOW; i++) {
                out[pos][0] = Math.max(out[pos][0], -a[i]);
            }
        }
        return out;
    }

    static double[] accel(double[] v) {
        double[] a = new double[v.length];
        for (int i = 1; i < v.length - 1; i++) {
            a[i] = (v[i + 1] - v[i - 1]) / (2 * DATA_DT);
        }
        return a;
    }

    private static double[] smooth(double[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            int lo = Math.max(0, i - 5);
            int hi = Math.min(v.length, i + 6);
            out[i] = mean(v, lo, hi - lo);
        }
        return out;
    }

    private static double mean(double[] v, int start, int len) {
        double s = 0;
        for (int i = start; i < start + len; i++) {
            s += v[i];
        }
        return s / len;
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
