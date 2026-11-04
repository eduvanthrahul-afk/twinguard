package com.twinguard.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Everything specific to one vehicle feature: its road or usage scenarios, physics, software, and
 * safety rules. The engine supplies the search, coverage, confirmation, shrinking, and scoring.
 */
public interface VehicleFeature {

    record Param(String key, String label, String unit, double min, double max, double nominal) {
    }

    record Channel(String key, String label, String unit) {
    }

    record SettingFault(String id, String faultClass, String title, Map<String, Double> overrides) {
    }

    record Release(String id, String title, String summary, Map<String, Double> overrides, Set<String> declaredChanges, String intent) {
    }

    /** What one version of the software did in one scenario. */
    record Result(Map<String, Double> metrics, List<double[]> trace) {
    }

    String id();

    String name();

    String summary();

    /** What this model leaves out. Shown next to the results. */
    String limitations();

    List<Param> params();

    /** The two scenario parameters, by index, that form the coverage map. */
    int coverageX();

    int coverageY();

    /** Values recorded over time for the charts. Each trace row is the time, then these values in order. */
    List<Channel> channels();

    /** How much simulated time passes in one control step. */
    double stepSeconds();

    /** The FMU contract: names of the signals the software reads. */
    List<String> inputNames();

    /** The FMU contract: names of the signals the software writes. */
    List<String> outputNames();

    Map<String, Double> defaultSettings();

    FeatureController logic(Map<String, Double> settings);

    /** The class that code mutation rewrites. It must have a public constructor taking a Map of settings. */
    Class<? extends FeatureController> logicClass();

    List<SettingFault> faults();

    List<Release> releases();

    Result simulate(FeatureController controller, double[] scenario, long seed, boolean withTrace);

    /** Applies the safety rules. Declared changes can excuse differences from v1, but never a fixed safety rule. */
    List<FeatureFinding> judge(double[] scenario, Result v1, Result v2, Set<String> declaredChanges);

    /** A scenario near the point where the software must act, for the search to favour. */
    double[] nearBoundary(Random rng);

    /** The conventional suite: a fixed, ordinary set of scenarios. */
    List<double[]> conventionalScenarios();

    boolean conventionalPass(double[] scenario, Result result);

    default Map<String, Double> settingsWith(Map<String, Double> overrides) {
        Map<String, Double> settings = new LinkedHashMap<>(defaultSettings());
        for (Map.Entry<String, Double> e : overrides.entrySet()) {
            if (!settings.containsKey(e.getKey())) {
                throw new IllegalArgumentException("Unknown setting for " + id() + ": " + e.getKey());
            }
            settings.put(e.getKey(), e.getValue());
        }
        return settings;
    }

    default Supplier<FeatureController> source(Map<String, Double> overrides) {
        Map<String, Double> settings = settingsWith(overrides);
        return () -> logic(settings);
    }

    default double[] uniform(Random rng) {
        List<Param> params = params();
        double[] s = new double[params.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = params.get(i).min() + rng.nextDouble() * (params.get(i).max() - params.get(i).min());
        }
        return s;
    }

    default double[] clamp(double[] s) {
        double[] out = s.clone();
        List<Param> params = params();
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.max(params.get(i).min(), Math.min(params.get(i).max(), out[i]));
        }
        return out;
    }

    default double[] nominal() {
        return params().stream().mapToDouble(Param::nominal).toArray();
    }

    /** Builds a conventional suite from every combination of the given values for each parameter. */
    static List<double[]> grid(double[][] values) {
        List<double[]> out = new ArrayList<>();
        out.add(new double[values.length]);
        for (int i = 0; i < values.length; i++) {
            List<double[]> next = new ArrayList<>();
            for (double[] partial : out) {
                for (double v : values[i]) {
                    double[] copy = partial.clone();
                    copy[i] = v;
                    next.add(copy);
                }
            }
            out = next;
        }
        return out;
    }
}
