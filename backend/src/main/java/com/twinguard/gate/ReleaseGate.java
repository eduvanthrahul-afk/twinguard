package com.twinguard.gate;

import com.twinguard.oracle.ChangeManifest;
import com.twinguard.oracle.Evaluation;
import com.twinguard.oracle.Evaluator;
import com.twinguard.oracle.Finding;
import com.twinguard.sim.Firmware;
import com.twinguard.sim.Simulator;
import com.twinguard.testgen.Coverage;
import com.twinguard.testgen.LegacySuite;
import com.twinguard.testgen.ScenarioSearch;
import com.twinguard.testgen.Shrinker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tests one candidate against the approved firmware and produces the release decision. */
public final class ReleaseGate {

    private static final int MAX_COUNTEREXAMPLES = 5;

    /** @param baseline the version the candidate is compared with */
    public record Target(String id, String title, String kind, Firmware firmware, ChangeManifest manifest, Firmware baseline) {

        public Target(String id, String title, String kind, Firmware firmware, ChangeManifest manifest) {
            this(id, title, kind, firmware, manifest, Firmware.approved());
        }
    }

    private ReleaseGate() {
    }

    public static GateReport run(Target target, int budget, ScenarioSearch.Strategy strategy, long rngSeed,
                                 ScenarioSearch.Listener listener) {
        Firmware baseline = target.baseline();
        List<String> changed = target.firmware().changedFrom(baseline);
        Evaluator evaluator = new Evaluator(baseline, target.firmware(), target.manifest());

        long start = System.nanoTime();
        LegacySuite.Result legacy = LegacySuite.run(target.firmware());
        ScenarioSearch.Config config = new ScenarioSearch.Config(strategy, budget, rngSeed, false, List.of());
        ScenarioSearch.Outcome outcome = new ScenarioSearch(evaluator, changed).run(config, listener);

        Map<Finding.Type, Integer> counts = new EnumMap<>(Finding.Type.class);
        Map<Finding.Type, Evaluation> worstByType = new LinkedHashMap<>();
        for (Evaluation e : outcome.detections()) {
            for (Finding f : e.findings()) {
                counts.merge(f.type(), 1, Integer::sum);
                worstByType.merge(f.type(), e, (a, b) -> b.fitness() > a.fitness() ? b : a);
            }
        }

        Shrinker shrinker = new Shrinker(evaluator);
        List<GateReport.Counterexample> counterexamples = new ArrayList<>();
        worstByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Finding.Type::ordinal)))
                .limit(MAX_COUNTEREXAMPLES)
                .forEach(entry -> counterexamples.add(counterexample(entry.getKey(), entry.getValue(),
                        baseline, target.firmware(), shrinker)));
        long computeMs = (System.nanoTime() - start) / 1_000_000;

        GateReport.Verdict verdict = verdictFor(counts);
        return new GateReport(target.id(), target.title(), target.kind(), verdict, reason(verdict, counts, outcome),
                changed, strategy.name(), legacy, outcome.evaluations(), outcome.simulations(), computeMs,
                outcome.detections().size(), counts,
                Math.round(outcome.coverage().coveredPercent() * 10.0) / 10.0,
                outcome.coverage().coveredCells(), Coverage.TOTAL_CELLS,
                outcome.coverage().snapshot(), counterexamples);
    }

    private static GateReport.Counterexample counterexample(Finding.Type type, Evaluation original,
                                                            Firmware baseline, Firmware candidate, Shrinker shrinker) {
        Evaluation minimal = shrinker.shrink(reorderedFor(type, original));
        Evaluation shown = minimal.findings().stream().anyMatch(f -> f.type() == type) ? minimal : original;
        Finding primary = shown.findings().stream().filter(f -> f.type() == type).findFirst().orElseThrow();
        return new GateReport.Counterexample(primary, shown.findings(), shown.scenario(), original.scenario(),
                GateReport.Metrics.of(shown.v1()), GateReport.Metrics.of(shown.v2()),
                Simulator.run(baseline, shown.scenario(), true).trace(),
                Simulator.run(candidate, shown.scenario(), true).trace());
    }

    /** Puts the requested finding first, because the shrinker preserves the first finding's type. */
    private static Evaluation reorderedFor(Finding.Type type, Evaluation e) {
        List<Finding> ordered = new ArrayList<>(e.findings());
        ordered.sort(Comparator.comparing((Finding f) -> f.type() != type));
        return new Evaluation(e.scenario(), e.v1(), e.v2(), ordered, e.divergence(), e.criticality(), e.simulations());
    }

    private static GateReport.Verdict verdictFor(Map<Finding.Type, Integer> counts) {
        boolean blocking = counts.keySet().stream().anyMatch(t -> t.severity != Finding.Severity.MEDIUM);
        if (blocking) {
            return GateReport.Verdict.BLOCK;
        }
        return counts.isEmpty() ? GateReport.Verdict.APPROVE : GateReport.Verdict.REVIEW;
    }

    private static String reason(GateReport.Verdict verdict, Map<Finding.Type, Integer> counts,
                                 ScenarioSearch.Outcome outcome) {
        return switch (verdict) {
            case BLOCK -> "Confirmed safety regressions in " + outcome.detections().size() + " of "
                    + outcome.evaluations() + " scenarios tested";
            case REVIEW -> "Only medium-severity differences found. An engineer should review them before release";
            case APPROVE -> "No confirmed regression in " + outcome.evaluations()
                    + " scenarios. This is evidence, not proof of safety";
        };
    }
}
