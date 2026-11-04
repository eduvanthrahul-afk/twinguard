package com.twinguard.gate;

import com.twinguard.oracle.Finding;
import com.twinguard.sim.RunResult;
import com.twinguard.sim.Scenario;
import com.twinguard.testgen.Coverage;
import com.twinguard.testgen.LegacySuite;
import java.util.List;
import java.util.Map;

/** The evidence behind one release decision. */
public record GateReport(
        String targetId,
        String targetTitle,
        String targetKind,
        Verdict verdict,
        String reason,
        List<String> changedSettings,
        String strategy,
        LegacySuite.Result legacy,
        int evaluations,
        int simulations,
        long computeMs,
        int failingScenarios,
        Map<Finding.Type, Integer> findingCounts,
        double coveragePercent,
        int coveredCells,
        int totalCells,
        List<Coverage.Cell> coverage,
        List<Counterexample> counterexamples) {

    public enum Verdict { BLOCK, REVIEW, APPROVE }

    /** The same report with animation pauses removed from the measured time. */
    public GateReport withComputeMs(long ms) {
        return new GateReport(targetId, targetTitle, targetKind, verdict, reason, changedSettings, strategy, legacy,
                evaluations, simulations, ms, failingScenarios, findingCounts, coveragePercent, coveredCells, totalCells,
                coverage, counterexamples);
    }

    /** The minimal failing scenario, with both versions' behaviour for the replay. */
    public record Counterexample(
            Finding primary,
            List<Finding> findings,
            Scenario scenario,
            Scenario originalScenario,
            Metrics v1,
            Metrics v2,
            List<RunResult.TracePoint> v1Trace,
            List<RunResult.TracePoint> v2Trace) {
    }

    /** JSON-safe metrics: an event that never happened becomes null. */
    public record Metrics(Double brakeOnsetS, Double fullBrakeS, double peakDecel, double peakBrakeRequest, double minGapM,
                          boolean collision, double impactKph, boolean braked, double maxSensorAgeMs,
                          int maxQueueDepth, int droppedMessages) {

        public static Metrics of(RunResult r) {
            return new Metrics(nullable(r.brakeOnsetS()), nullable(r.fullBrakeS()), r.peakDecel(), r.peakBrakeRequest(), r.minGapM(),
                    r.collision(), r.impactKph(), r.braked(), r.maxSensorAgeMs(), r.maxQueueDepth(), r.droppedMessages());
        }

        private static Double nullable(double v) {
            return Double.isNaN(v) ? null : v;
        }
    }
}
