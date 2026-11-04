package com.twinguard.campaign;

import com.twinguard.campaign.CampaignReport.ClassScore;
import com.twinguard.campaign.CampaignReport.ControlResult;
import com.twinguard.campaign.CampaignReport.CurvePoint;
import com.twinguard.campaign.CampaignReport.FaultResult;
import com.twinguard.campaign.CampaignReport.Score;
import com.twinguard.campaign.CampaignReport.SurvivorKind;
import com.twinguard.catalog.Fault;
import com.twinguard.catalog.FaultCatalog;
import com.twinguard.catalog.Release;
import com.twinguard.catalog.ReleaseCatalog;
import com.twinguard.oracle.ChangeManifest;
import com.twinguard.oracle.Evaluation;
import com.twinguard.oracle.Evaluator;
import com.twinguard.sim.Firmware;
import com.twinguard.sim.RunResult;
import com.twinguard.sim.Scenario;
import com.twinguard.testgen.ScenarioSearch;
import com.twinguard.testgen.ScenarioSearch.Strategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

/**
 * Measures the test process itself: injects every generated fault, records whether each method
 * finds it and how quickly, then spends extra budget on development faults that survived.
 * Held-out faults never influence the corpus, so their score is the fairest estimate.
 */
public final class MutationCampaign {

    public static final int DEFAULT_TRIALS = 5;
    public static final int DEFAULT_BUDGET = 400;
    private static final int DEEP_RECHECK_MULTIPLIER = 10;
    private static final int[] CURVE_POINTS = {5, 10, 25, 50, 100, 200, 400};
    private static final int CONTROL_SCENARIOS = 2000;
    private static final int CONTROL_SEEDS = 10;
    private static final double SAME_BEHAVIOUR = 1e-6;

    private record Trial(int firstDetection, String finding, Scenario scenario, double maxBehaviourChange) {
    }

    private final int trials;
    private final int budget;
    private final AtomicLong simulations = new AtomicLong();

    public MutationCampaign(int trials, int budget) {
        this.trials = trials;
        this.budget = budget;
    }

    public CampaignReport run(BiConsumer<String, Double> progress) {
        long start = System.nanoTime();
        List<Fault> faults = FaultCatalog.all();
        List<Fault> dev = faults.stream().filter(f -> !f.holdout()).toList();
        List<Fault> held = faults.stream().filter(Fault::holdout).toList();

        Map<String, List<Trial>> random = sweep(faults, Strategy.RANDOM, List.of(), "Random baseline", 0.0, 0.25, progress);
        Map<String, List<Trial>> search = sweep(faults, Strategy.SEARCH, List.of(), "Coverage-guided search", 0.25, 0.25, progress);
        Map<String, List<Trial>> aware = sweep(faults, Strategy.CHANGE_AWARE, List.of(), "Change-aware search", 0.5, 0.25, progress);

        progress.accept("Deep recheck of surviving development faults", 0.75);
        List<String> hardened = new ArrayList<>();
        List<Scenario> corpus = harden(dev, aware, hardened);

        Map<String, List<Trial>> finalResults = sweep(faults, Strategy.CHANGE_AWARE, corpus, "Re-scoring every fault", 0.8, 0.12, progress);

        // A fault only counts as "no behaviour change" if the deep budget also saw none.
        progress.accept("Classifying survivors", 0.92);
        Map<String, SurvivorKind> survivors = classifySurvivors(faults, finalResults);

        progress.accept("Measuring false alarms on known-safe releases", 0.96);
        List<ControlResult> controls = controls();

        List<ClassScore> byClass = new ArrayList<>();
        for (Fault.FaultClass c : Fault.FaultClass.values()) {
            List<Fault> members = faults.stream().filter(f -> f.faultClass() == c).toList();
            byClass.add(new ClassScore(c.name(), c.label, score(members, finalResults)));
        }

        List<FaultResult> results = faults.stream()
                .map(f -> faultResult(f, finalResults.get(f.id()), random.get(f.id()), hardened.contains(f.id()), survivors.get(f.id())))
                .toList();

        int noChange = (int) survivors.values().stream().filter(k -> k == SurvivorKind.NO_BEHAVIOUR_CHANGE).count();
        Score overall = score(faults, finalResults);
        long computeMs = (System.nanoTime() - start) / 1_000_000;
        progress.accept("Done", 1.0);
        return new CampaignReport(faults.size(), trials, budget,
                overall, Score.of(overall.detected(), faults.size() - noChange), noChange,
                score(dev, finalResults), score(held, finalResults),
                score(held, aware), score(faults, aware),
                corpus.size(), hardened, byClass,
                curve(faults, random, search, aware), curve(held, random, search, aware),
                results, controls,
                simulations.get(), computeMs, simulations.get() * 1000.0 / Math.max(1, computeMs));
    }

    private Map<String, List<Trial>> sweep(List<Fault> faults, Strategy strategy, List<Scenario> corpus, String label,
                                           double progressStart, double progressSpan, BiConsumer<String, Double> progress) {
        Map<String, List<Trial>> out = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();
        faults.parallelStream().forEach(f -> {
            List<Trial> list = IntStream.range(0, trials)
                    .mapToObj(t -> trial(f, strategy, budget, 1000L * (t + 1) + f.id().hashCode(), corpus))
                    .toList();
            out.put(f.id(), list);
            progress.accept(label, progressStart + progressSpan * done.incrementAndGet() / faults.size());
        });
        return out;
    }

    private Trial trial(Fault f, Strategy strategy, int scenarios, long seed, List<Scenario> corpus) {
        Firmware candidate = f.firmware();
        Firmware baseline = Firmware.approved();
        Evaluator evaluator = new Evaluator(baseline, candidate, ChangeManifest.NONE);
        ScenarioSearch search = new ScenarioSearch(evaluator, candidate.changedFrom(baseline));
        double[] maxChange = {0};
        ScenarioSearch.Outcome outcome = search.run(
                new ScenarioSearch.Config(strategy, scenarios + corpus.size(), seed, true, corpus),
                (e, done, coverage) -> {
                    maxChange[0] = Math.max(maxChange[0], behaviourChange(e.v1(), e.v2()));
                    return true;
                });
        simulations.addAndGet(outcome.simulations());
        if (outcome.firstDetectionAt() < 0) {
            return new Trial(-1, null, null, maxChange[0]);
        }
        Evaluation first = outcome.detections().get(0);
        return new Trial(outcome.firstDetectionAt(), first.findings().get(0).type().name(), first.scenario(), maxChange[0]);
    }

    public static double behaviourChange(RunResult a, RunResult b) {
        if (a.braked() != b.braked() || a.collision() != b.collision() || a.controllerCrashed() != b.controllerCrashed()) {
            return Double.MAX_VALUE;
        }
        double change = Math.abs(a.minGapM() - b.minGapM()) + Math.abs(a.peakDecel() - b.peakDecel())
                + Math.abs(a.impactKph() - b.impactKph()) + Math.abs(a.peakBrakeRequest() - b.peakBrakeRequest());
        if (a.braked()) {
            change += Math.abs(a.brakeOnsetS() - b.brakeOnsetS());
        }
        return change;
    }

    /** Spends a larger budget on development faults that survived, and keeps any scenario that catches one. */
    private List<Scenario> harden(List<Fault> dev, Map<String, List<Trial>> aware, List<String> hardened) {
        List<Scenario> corpus = new ArrayList<>();
        for (Fault f : dev) {
            if (isDetected(aware.get(f.id()))) {
                continue;
            }
            Trial deep = trial(f, Strategy.CHANGE_AWARE, budget * DEEP_RECHECK_MULTIPLIER, 99L + f.id().hashCode(), List.of());
            if (deep.firstDetection() > 0) {
                corpus.add(deep.scenario());
                hardened.add(f.id());
            }
        }
        return corpus;
    }

    private Map<String, SurvivorKind> classifySurvivors(List<Fault> faults, Map<String, List<Trial>> results) {
        Map<String, SurvivorKind> out = new ConcurrentHashMap<>();
        for (Fault f : faults) {
            if (isDetected(results.get(f.id()))) {
                continue;
            }
            Trial deep = trial(f, Strategy.CHANGE_AWARE, budget * DEEP_RECHECK_MULTIPLIER, 77L + f.id().hashCode(), List.of());
            boolean changed = deep.maxBehaviourChange() > SAME_BEHAVIOUR
                    || results.get(f.id()).stream().anyMatch(t -> t.maxBehaviourChange() > SAME_BEHAVIOUR);
            out.put(f.id(), changed ? SurvivorKind.WITHIN_TOLERANCE : SurvivorKind.NO_BEHAVIOUR_CHANGE);
        }
        return out;
    }

    private boolean isDetected(List<Trial> list) {
        return list.stream().filter(t -> t.firstDetection() > 0).count() * 2 > list.size();
    }

    private Score score(List<Fault> faults, Map<String, List<Trial>> results) {
        int detected = (int) faults.stream().filter(f -> isDetected(results.get(f.id()))).count();
        return Score.of(detected, faults.size());
    }

    private List<CurvePoint> curve(List<Fault> faults, Map<String, List<Trial>> random,
                                   Map<String, List<Trial>> search, Map<String, List<Trial>> aware) {
        List<CurvePoint> points = new ArrayList<>();
        for (int p : CURVE_POINTS) {
            if (p <= budget) {
                points.add(new CurvePoint(p, rateAt(faults, random, p), rateAt(faults, search, p), rateAt(faults, aware, p)));
            }
        }
        return points;
    }

    private double rateAt(List<Fault> faults, Map<String, List<Trial>> results, int scenarios) {
        long hits = 0;
        for (Fault f : faults) {
            hits += results.get(f.id()).stream()
                    .filter(t -> t.firstDetection() > 0 && t.firstDetection() <= scenarios).count();
        }
        return Math.round(1000.0 * hits / (faults.size() * trials)) / 10.0;
    }

    private FaultResult faultResult(Fault f, List<Trial> finalTrials, List<Trial> randomTrials, boolean hardened, SurvivorKind kind) {
        int detectedTrials = (int) finalTrials.stream().filter(t -> t.firstDetection() > 0).count();
        String finding = finalTrials.stream().filter(t -> t.finding() != null).map(Trial::finding).findFirst().orElse(null);
        return new FaultResult(f.id(), f.faultClass().name(), f.faultClass().label, f.title(), f.description(),
                f.holdout(), isDetected(finalTrials), detectedTrials, mean(finalTrials), mean(randomTrials), finding, hardened, kind);
    }

    private static Double mean(List<Trial> list) {
        double m = list.stream().filter(t -> t.firstDetection() > 0).mapToInt(Trial::firstDetection).average().orElse(Double.NaN);
        return Double.isNaN(m) ? null : Math.round(m * 10.0) / 10.0;
    }

    private List<ControlResult> controls() {
        List<ControlResult> out = new ArrayList<>();
        out.add(control("Identical firmware (no change)", Firmware.approved(), ChangeManifest.NONE));
        Release safe = ReleaseCatalog.find("v2.1-comfort").orElseThrow();
        out.add(control(safe.title() + " (declared change)", safe.firmware(), safe.manifest()));
        return out;
    }

    private ControlResult control(String name, Firmware firmware, ChangeManifest manifest) {
        Firmware baseline = Firmware.approved();
        Evaluator evaluator = new Evaluator(baseline, firmware, manifest);
        List<String> changed = firmware.changedFrom(baseline);
        // Several seeds, because a rare false alarm can easily be missed by a single lucky run.
        List<ScenarioSearch.Outcome> runs = IntStream.range(0, CONTROL_SEEDS).parallel()
                .mapToObj(i -> new ScenarioSearch(evaluator, changed).run(
                        new ScenarioSearch.Config(Strategy.CHANGE_AWARE, CONTROL_SCENARIOS, 5L + i * 104_729L, false, List.of()), null))
                .toList();
        runs.forEach(o -> simulations.addAndGet(o.simulations()));
        int scenarios = runs.stream().mapToInt(ScenarioSearch.Outcome::evaluations).sum();
        int falseAlarms = runs.stream().mapToInt(o -> o.detections().size()).sum();
        return new ControlResult(name, scenarios, falseAlarms, falseAlarms == 0);
    }
}
