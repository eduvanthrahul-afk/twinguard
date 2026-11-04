package com.twinguard.feature;

import com.twinguard.campaign.CampaignReport.Score;
import com.twinguard.campaign.CampaignReport.SurvivorKind;
import com.twinguard.feature.FeatureEngine.Strategy;
import com.twinguard.mutation.CodeMutator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/** Scores the gate for one feature: designed faults, real code mutants, random testing, and false alarms. */
public final class FeatureCampaign {

    public static final int TRIALS = 3;
    public static final int BUDGET = 400;
    private static final int DEEP_MULTIPLIER = 5;
    private static final int CONTROL_RUNS = 4;
    private static final int CONTROL_SCENARIOS = 2000;
    private static final double SAME_BEHAVIOUR = 1e-6;

    public record MutantResult(String id, String method, int line, String operator, String description, String sourceLine,
                               boolean detected, boolean detectedByRandom, Double meanScenariosToDetect, String firstFinding, SurvivorKind survivorKind) {
    }

    public record FaultResult(String id, String faultClass, String title, boolean detected, boolean detectedByRandom, String firstFinding) {
    }

    public record Report(String featureId, int trials, int budget, Score faults, Score faultsRandom, Score mutants, Score mutantsRandom,
                         Score excludingNoChange, int noChangeMutants, int falseAlarms, int controlScenarios,
                         List<FaultResult> faultResults, List<MutantResult> mutantResults, long simulations, long computeMs) {
    }

    private record Trial(int first, String finding, double change) {
    }

    private final VehicleFeature feature;
    private final Supplier<FeatureController> baseline;
    private final AtomicLong simulations = new AtomicLong();

    public FeatureCampaign(VehicleFeature feature) {
        this.feature = feature;
        this.baseline = feature.source(Map.of());
    }

    public Report run(BiConsumer<String, Double> progress) {
        long start = System.nanoTime();
        List<VehicleFeature.SettingFault> faults = feature.faults();
        List<CodeMutator.Generated> mutants = FeatureMutants.of(feature);

        progress.accept(feature.name() + ": designed faults", 0.0);
        List<FaultResult> faultResults = faults.parallelStream().map(f -> {
            Supplier<FeatureController> source = feature.source(f.overrides());
            List<Trial> guided = trials(source, Strategy.SEARCH, f.id());
            return new FaultResult(f.id(), f.faultClass(), f.title(), detected(guided), detected(trials(source, Strategy.RANDOM, f.id())), finding(guided));
        }).toList();

        progress.accept(feature.name() + ": code mutants", 0.35);
        List<MutantResult> mutantResults = mutants.parallelStream().map(m -> {
            Supplier<FeatureController> source = FeatureMutants.source(feature, m);
            List<Trial> guided = trials(source, Strategy.SEARCH, m.id());
            List<Trial> random = trials(source, Strategy.RANDOM, m.id());
            boolean caught = detected(guided);
            double mean = guided.stream().filter(t -> t.first() > 0).mapToInt(Trial::first).average().orElse(Double.NaN);
            SurvivorKind kind = null;
            if (!caught) {
                Trial deep = trial(source, Strategy.SEARCH, BUDGET * DEEP_MULTIPLIER, 31L + m.id().hashCode());
                boolean changed = deep.change() > SAME_BEHAVIOUR || guided.stream().anyMatch(t -> t.change() > SAME_BEHAVIOUR);
                kind = changed ? SurvivorKind.WITHIN_TOLERANCE : SurvivorKind.NO_BEHAVIOUR_CHANGE;
            }
            return new MutantResult(m.id(), m.method(), m.line(), m.operator(), m.description(), m.sourceLine(), caught, detected(random),
                    Double.isNaN(mean) ? null : Math.round(mean * 10.0) / 10.0, finding(guided), kind);
        }).toList();

        progress.accept(feature.name() + ": false alarms", 0.8);
        int[] control = controls();

        int noChange = (int) mutantResults.stream().filter(m -> m.survivorKind() == SurvivorKind.NO_BEHAVIOUR_CHANGE).count();
        int caughtMutants = (int) mutantResults.stream().filter(MutantResult::detected).count();
        Report report = new Report(feature.id(), TRIALS, BUDGET,
                Score.of((int) faultResults.stream().filter(FaultResult::detected).count(), faults.size()),
                Score.of((int) faultResults.stream().filter(FaultResult::detectedByRandom).count(), faults.size()),
                Score.of(caughtMutants, mutants.size()),
                Score.of((int) mutantResults.stream().filter(MutantResult::detectedByRandom).count(), mutants.size()),
                Score.of(caughtMutants, mutants.size() - noChange), noChange, control[0], control[1],
                faultResults, mutantResults, simulations.get(), (System.nanoTime() - start) / 1_000_000);
        progress.accept(feature.name() + ": done", 1.0);
        return report;
    }

    private List<Trial> trials(Supplier<FeatureController> source, Strategy strategy, String id) {
        return IntStream.range(0, TRIALS).mapToObj(t -> trial(source, strategy, BUDGET, 5000L * (t + 1) + id.hashCode())).toList();
    }

    private Trial trial(Supplier<FeatureController> source, Strategy strategy, int budget, long seed) {
        FeatureEngine.Outcome o = new FeatureEngine(feature, baseline, source, Set.of()).search(strategy, budget, seed, true, null);
        simulations.addAndGet(o.simulations());
        String finding = o.detections().isEmpty() ? null : o.detections().get(0).findings().get(0).type();
        return new Trial(o.firstDetectionAt(), finding, o.maxChange());
    }

    private static boolean detected(List<Trial> list) {
        return list.stream().filter(t -> t.first() > 0).count() * 2 > list.size();
    }

    private static String finding(List<Trial> list) {
        return list.stream().map(Trial::finding).filter(f -> f != null).findFirst().orElse(null);
    }

    /** Runs safe software through the gate, on identical code and on declared changes. */
    private int[] controls() {
        List<Supplier<FeatureEngine>> engines = new ArrayList<>();
        engines.add(() -> new FeatureEngine(feature, baseline, baseline, Set.of()));
        for (VehicleFeature.Release r : feature.releases()) {
            if (!r.declaredChanges().isEmpty()) {
                engines.add(() -> new FeatureEngine(feature, baseline, feature.source(r.overrides()), r.declaredChanges()));
            }
        }
        int alarms = 0;
        int scenarios = 0;
        for (Supplier<FeatureEngine> engine : engines) {
            for (int run = 0; run < CONTROL_RUNS; run++) {
                FeatureEngine.Outcome o = engine.get().search(Strategy.SEARCH, CONTROL_SCENARIOS, 104_729L * (run + 1), false, null);
                simulations.addAndGet(o.simulations());
                alarms += o.detections().size();
                scenarios += o.evaluations();
            }
        }
        return new int[] {alarms, scenarios};
    }
}
