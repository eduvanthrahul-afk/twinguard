package com.twinguard.campaign;

import java.util.List;

/** How much the test process can be trusted, measured by injecting known faults into it. */
public record CampaignReport(
        int totalFaults,
        int trials,
        int budget,
        Score overall,
        Score excludingNoBehaviourChange,
        int noBehaviourChangeFaults,
        Score development,
        Score holdout,
        Score holdoutBeforeHardening,
        Score overallBeforeHardening,
        int corpusScenariosAdded,
        List<String> hardenedFaults,
        List<ClassScore> byClass,
        List<CurvePoint> curve,
        List<CurvePoint> holdoutCurve,
        List<FaultResult> faults,
        List<ControlResult> controls,
        long simulations,
        long computeMs,
        double simulationsPerSecond) {

    /** Detection rate with a Wilson 95% interval. */
    public record Score(int detected, int total, double percent, double lower95, double upper95) {

        public static Score of(int detected, int total) {
            if (total == 0) {
                return new Score(0, 0, 0, 0, 0);
            }
            double p = (double) detected / total;
            double z = 1.96;
            double denom = 1 + z * z / total;
            double center = (p + z * z / (2.0 * total)) / denom;
            double margin = z * Math.sqrt(p * (1 - p) / total + z * z / (4.0 * total * total)) / denom;
            return new Score(detected, total, round(p * 100),
                    round(Math.max(0, center - margin) * 100), round(Math.min(1, center + margin) * 100));
        }

        private static double round(double v) {
            return Math.round(v * 10.0) / 10.0;
        }
    }

    public record ClassScore(String faultClass, String label, Score score) {
    }

    /** Mean percentage of faults found by each method within a given number of scenarios. */
    public record CurvePoint(int scenarios, double random, double search, double changeAware) {
    }

    public record FaultResult(String id, String faultClass, String classLabel, String title, String description,
                              boolean holdout, boolean detected, int detectedTrials,
                              Double meanScenariosToDetect, Double randomMeanScenariosToDetect,
                              String firstFinding, boolean hardened, SurvivorKind survivorKind) {
    }

    /** Why a fault was not caught. The two kinds mean very different things to an engineer. */
    public enum SurvivorKind {
        /** Braking behaved identically in every scenario tested. Harmless, or needs conditions we do not simulate. */
        NO_BEHAVIOUR_CHANGE,
        /** Braking changed, but stayed inside the tolerances. A real blind spot of this gate. */
        WITHIN_TOLERANCE
    }

    /** A release known to be safe, used to measure false alarms. */
    public record ControlResult(String name, int scenarios, int falseAlarms, boolean passed) {
    }
}
