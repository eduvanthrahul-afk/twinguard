package com.twinguard.feature;

import com.twinguard.feature.VehicleFeature.Param;
import com.twinguard.feature.VehicleFeature.Result;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Tests one software version against another for any feature: runs both on identical inputs,
 * confirms findings on repeat seeds, searches for failures, and tracks coverage.
 */
public final class FeatureEngine {

    public enum Strategy { RANDOM, SEARCH }

    public static final int BINS = 8;
    private static final int CONFIRMATION_SEEDS = 3;
    private static final int REQUIRED_CONFIRMATIONS = 2;
    private static final long SEED_STEP = 7_919;

    public record Evaluation(double[] scenario, long seed, Result v1, Result v2, List<FeatureFinding> findings, double divergence, int simulations) {
        public boolean detected() {
            return !findings.isEmpty();
        }
    }

    public record Cell(int x, int y, int tests, int failures) {
    }

    public record Outcome(List<Evaluation> detections, List<Cell> coverage, int evaluations, int simulations, int firstDetectionAt, double maxChange) {
    }

    public interface Listener {
        void onEvaluation(Evaluation evaluation, int done, Supplier<List<Cell>> coverage);
    }

    /** Catches anything the software does wrong at run time, which on a real ECU would be a software fault. */
    private static final class Guarded implements FeatureController {
        private final FeatureController inner;
        private final int outputs;
        boolean crashed;

        Guarded(FeatureController inner, int outputs) {
            this.inner = inner;
            this.outputs = outputs;
        }

        @Override
        public double[] step(double[] inputs) {
            if (crashed) {
                return new double[outputs];
            }
            try {
                double[] out = inner.step(inputs);
                for (double v : out) {
                    if (Double.isNaN(v)) {
                        crashed = true;
                        return new double[outputs];
                    }
                }
                return out;
            } catch (RuntimeException e) {
                crashed = true;
                return new double[outputs];
            }
        }
    }

    private final VehicleFeature feature;
    private final Supplier<FeatureController> baseline;
    private final Supplier<FeatureController> candidate;
    private final Set<String> declared;

    public FeatureEngine(VehicleFeature feature, Supplier<FeatureController> baseline, Supplier<FeatureController> candidate, Set<String> declared) {
        this.feature = feature;
        this.baseline = baseline;
        this.candidate = candidate;
        this.declared = declared;
    }

    public Result run(Supplier<FeatureController> source, double[] scenario, long seed, boolean withTrace) {
        FeatureController controller = source.get();
        try {
            Guarded guarded = new Guarded(controller, feature.outputNames().size());
            Result r = feature.simulate(guarded, scenario, seed, withTrace);
            Map<String, Double> metrics = new LinkedHashMap<>(r.metrics());
            metrics.put("crashed", guarded.crashed ? 1.0 : 0.0);
            return new Result(metrics, r.trace());
        } finally {
            if (controller instanceof AutoCloseable closeable) {
                closeQuietly(closeable);
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            // The run has already finished, and a failure to release its memory can't change the result.
        }
    }

    private List<FeatureFinding> judge(double[] s, Result a, Result b) {
        List<FeatureFinding> out = new ArrayList<>();
        if (b.metrics().get("crashed") > 0 && a.metrics().get("crashed") == 0) {
            out.add(FeatureFinding.critical(FeatureFinding.CRASH, "Software crash", "The software crashed or produced an invalid output, and stopped acting"));
        }
        out.addAll(feature.judge(s, a, b, declared));
        return out;
    }

    public Evaluation evaluate(double[] s, long seed) {
        Result a = run(baseline, s, seed, false);
        Result b = run(candidate, s, seed, false);
        int simulations = 2;
        List<FeatureFinding> first = judge(s, a, b);
        List<FeatureFinding> confirmed = List.of();
        if (!first.isEmpty()) {
            Map<String, Integer> votes = new HashMap<>();
            first.forEach(f -> votes.merge(f.type(), 1, Integer::sum));
            for (int i = 1; i < CONFIRMATION_SEEDS; i++) {
                long repeat = seed + i * SEED_STEP;
                Result r1 = run(baseline, s, repeat, false);
                Result r2 = run(candidate, s, repeat, false);
                simulations += 2;
                judge(s, r1, r2).forEach(f -> votes.merge(f.type(), 1, Integer::sum));
            }
            confirmed = first.stream().filter(f -> votes.get(f.type()) >= REQUIRED_CONFIRMATIONS).toList();
        }
        return new Evaluation(s, seed, a, b, confirmed, divergence(a, b), simulations);
    }

    /** How differently the versions behaved, as a relative change over every metric. */
    public static double divergence(Result a, Result b) {
        double score = 0;
        for (Map.Entry<String, Double> e : a.metrics().entrySet()) {
            double x = e.getValue();
            double y = b.metrics().getOrDefault(e.getKey(), x);
            score += Math.abs(x - y) / (Math.abs(x) + Math.abs(y) + 1.0);
        }
        return score;
    }

    public Outcome search(Strategy strategy, int budget, long rngSeed, boolean stopAtFirst, Listener listener) {
        Random rng = new Random(rngSeed);
        Map<Integer, int[]> cells = new HashMap<>();
        List<Evaluation> elites = new ArrayList<>();
        List<Evaluation> detections = new ArrayList<>();
        int simulations = 0;
        int first = -1;
        int done = 0;
        double maxChange = 0;

        while (done < budget) {
            double[] s = strategy == Strategy.RANDOM ? feature.uniform(rng) : guided(rng, cells, elites);
            Evaluation e = evaluate(s, rng.nextLong());
            done++;
            simulations += e.simulations();
            maxChange = Math.max(maxChange, e.divergence());

            int[] cell = cells.computeIfAbsent(key(s), k -> new int[2]);
            cell[0]++;
            if (e.detected()) {
                cell[1]++;
                detections.add(e);
                if (first < 0) {
                    first = done;
                }
            }
            if (e.divergence() > 0) {
                elites.add(e);
                if (elites.size() > 64) {
                    elites.sort((p, q) -> Double.compare(q.divergence(), p.divergence()));
                    elites.remove(elites.size() - 1);
                }
            }
            if (listener != null) {
                listener.onEvaluation(e, done, () -> snapshot(cells));
            }
            if (stopAtFirst && e.detected()) {
                break;
            }
        }

        return new Outcome(detections, snapshot(cells), done, simulations, first, maxChange);
    }

    private static List<Cell> snapshot(Map<Integer, int[]> cells) {
        List<Cell> coverage = new ArrayList<>(cells.size());
        cells.forEach((k, v) -> coverage.add(new Cell(k / BINS, k % BINS, v[0], v[1])));
        return coverage;
    }

    private double[] guided(Random rng, Map<Integer, int[]> cells, List<Evaluation> elites) {
        double r = rng.nextDouble();
        if (r < 0.3) {
            double[] s = feature.uniform(rng);
            for (int i = 0; i < 8 && cells.containsKey(key(s)); i++) {
                s = feature.uniform(rng);
            }
            return s;
        }
        if (r < 0.65 || elites.isEmpty()) {
            return feature.clamp(feature.nearBoundary(rng));
        }
        double[] base = elites.get(rng.nextInt(elites.size())).scenario();
        double[] s = base.clone();
        List<Param> params = feature.params();
        for (int i = 0; i < s.length; i++) {
            s[i] += rng.nextGaussian() * 0.08 * (params.get(i).max() - params.get(i).min());
        }
        return feature.clamp(s);
    }

    private int key(double[] s) {
        return bin(s, feature.coverageX()) * BINS + bin(s, feature.coverageY());
    }

    private int bin(double[] s, int index) {
        Param p = feature.params().get(index);
        int b = (int) ((s[index] - p.min()) / (p.max() - p.min()) * BINS);
        return Math.max(0, Math.min(BINS - 1, b));
    }

    /** Reduces a failure to its simplest form: as close to ordinary conditions as it can be while still failing. */
    public Evaluation shrink(Evaluation failure, String type) {
        double[] s = failure.scenario().clone();
        double[] nominal = feature.nominal();
        List<Param> params = feature.params();
        for (int i = 0; i < s.length; i++) {
            double[] attempt = s.clone();
            attempt[i] = nominal[i];
            if (stillFails(attempt, failure.seed(), type)) {
                s = attempt;
                continue;
            }
            double failing = s[i];
            double passing = nominal[i];
            for (int step = 0; step < 5; step++) {
                double mid = (failing + passing) / 2;
                attempt = s.clone();
                attempt[i] = mid;
                if (stillFails(attempt, failure.seed(), type)) {
                    failing = mid;
                } else {
                    passing = mid;
                }
            }
            s[i] = failing;
            double unit = (params.get(i).max() - params.get(i).min()) / 40;
            attempt = s.clone();
            attempt[i] = Math.round(failing / unit) * unit;
            attempt = feature.clamp(attempt);
            if (stillFails(attempt, failure.seed(), type)) {
                s = attempt;
            }
        }
        return evaluate(s, failure.seed());
    }

    private boolean stillFails(double[] s, long seed, String type) {
        return evaluate(s, seed).findings().stream().anyMatch(f -> f.type().equals(type));
    }
}
