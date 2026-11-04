package com.twinguard.feature;

import com.twinguard.feature.FeatureEngine.Cell;
import com.twinguard.gate.GateReport;
import java.util.List;
import java.util.Map;

/** The evidence behind one release decision for a plugin feature. */
public record FeatureReport(
        String featureId,
        String targetId,
        String targetTitle,
        GateReport.Verdict verdict,
        String reason,
        List<String> changedSettings,
        int conventionalPassed,
        int conventionalTotal,
        int evaluations,
        int simulations,
        long computeMs,
        int failingScenarios,
        Map<String, Integer> findingCounts,
        int coveredCells,
        int totalCells,
        List<Cell> coverage,
        List<Counterexample> counterexamples) {

    public record Counterexample(FeatureFinding primary, List<FeatureFinding> findings, Map<String, Double> scenario,
                                 Map<String, Double> v1, Map<String, Double> v2,
                                 List<double[]> v1Trace, List<double[]> v2Trace) {
    }
}
