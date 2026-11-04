package com.twinguard.feature;

import com.twinguard.feature.FeatureEngine.Evaluation;
import com.twinguard.feature.VehicleFeature.Param;
import com.twinguard.feature.VehicleFeature.Result;
import com.twinguard.gate.GateReport;
import com.twinguard.oracle.Finding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Produces a release decision for a candidate of any plugin feature. */
public final class FeatureGate {

    private static final int MAX_COUNTEREXAMPLES = 5;
    private static final long CONVENTIONAL_SEED = 100;

    private FeatureGate() {
    }

    public static FeatureReport run(VehicleFeature feature, String targetId, String title, List<String> changed,
                                    Supplier<FeatureController> baseline, Supplier<FeatureController> candidate,
                                    Set<String> declared, int budget, long seed, FeatureEngine.Listener listener) {
        long start = System.nanoTime();
        FeatureEngine engine = new FeatureEngine(feature, baseline, candidate, declared);

        List<double[]> suite = feature.conventionalScenarios();
        int passed = 0;
        for (double[] s : suite) {
            Result r = engine.run(candidate, s, CONVENTIONAL_SEED, false);
            if (r.metrics().get("crashed") == 0 && feature.conventionalPass(s, r)) {
                passed++;
            }
        }

        FeatureEngine.Outcome outcome = engine.search(FeatureEngine.Strategy.SEARCH, budget, seed, false, listener);

        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Evaluation> worst = new LinkedHashMap<>();
        Map<String, FeatureFinding> labels = new LinkedHashMap<>();
        for (Evaluation e : outcome.detections()) {
            for (FeatureFinding f : e.findings()) {
                counts.merge(f.type(), 1, Integer::sum);
                worst.merge(f.type(), e, (a, b) -> b.divergence() > a.divergence() ? b : a);
                labels.putIfAbsent(f.type(), f);
            }
        }

        List<FeatureReport.Counterexample> examples = new ArrayList<>();
        labels.values().stream()
                .sorted(Comparator.comparing(FeatureFinding::severity))
                .limit(MAX_COUNTEREXAMPLES)
                .forEach(f -> examples.add(counterexample(feature, engine, baseline, candidate, f, worst.get(f.type()))));

        GateReport.Verdict verdict = counts.isEmpty() ? GateReport.Verdict.APPROVE
                : labels.values().stream().anyMatch(f -> f.severity() != Finding.Severity.MEDIUM) ? GateReport.Verdict.BLOCK : GateReport.Verdict.REVIEW;
        String reason = switch (verdict) {
            case BLOCK -> "Confirmed safety regressions in " + outcome.detections().size() + " of " + outcome.evaluations() + " scenarios tested";
            case REVIEW -> "Only medium-severity differences found. An engineer should review them before release";
            case APPROVE -> "No confirmed regression in " + outcome.evaluations() + " scenarios. This is evidence, not proof of safety";
        };
        return new FeatureReport(feature.id(), targetId, title, verdict, reason, changed, passed, suite.size(), outcome.evaluations(),
                outcome.simulations(), (System.nanoTime() - start) / 1_000_000, outcome.detections().size(), counts,
                outcome.coverage().size(), FeatureEngine.BINS * FeatureEngine.BINS, outcome.coverage(), examples);
    }

    private static FeatureReport.Counterexample counterexample(VehicleFeature feature, FeatureEngine engine, Supplier<FeatureController> baseline,
                                                               Supplier<FeatureController> candidate, FeatureFinding finding, Evaluation original) {
        Evaluation minimal = engine.shrink(original, finding.type());
        Evaluation shown = minimal.findings().stream().anyMatch(f -> f.type().equals(finding.type())) ? minimal : original;
        FeatureFinding primary = shown.findings().stream().filter(f -> f.type().equals(finding.type())).findFirst().orElse(finding);
        Map<String, Double> params = new LinkedHashMap<>();
        List<Param> list = feature.params();
        for (int i = 0; i < list.size(); i++) {
            params.put(list.get(i).key(), shown.scenario()[i]);
        }
        return new FeatureReport.Counterexample(primary, shown.findings(), params, shown.v1().metrics(), shown.v2().metrics(),
                engine.run(baseline, shown.scenario(), shown.seed(), true).trace(),
                engine.run(candidate, shown.scenario(), shown.seed(), true).trace());
    }
}
