package com.twinguard.mutation;

import com.twinguard.campaign.CampaignReport.Score;
import com.twinguard.campaign.CampaignReport.SurvivorKind;
import com.twinguard.campaign.MutationCampaign;
import com.twinguard.mutation.CodeMutationReport.MutantResult;
import com.twinguard.mutation.CodeMutationReport.OperatorScore;
import com.twinguard.oracle.ChangeManifest;
import com.twinguard.oracle.Evaluation;
import com.twinguard.oracle.Evaluator;
import com.twinguard.sim.Firmware;
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
 * Scores the gate on bugs nobody picked by hand. These mutants are never used to tune the test
 * generator or the safety checks, so this score works as an independent test of them.
 */
public final class CodeMutationCampaign {

    public static final int DEFAULT_TRIALS = 3;
    public static final int DEFAULT_BUDGET = 400;
    private static final int DEEP_RECHECK_MULTIPLIER = 10;
    private static final double SAME_BEHAVIOUR = 1e-6;

    private record Trial(int firstDetection, String finding, boolean crashed, double maxBehaviourChange) {
    }

    private final int trials;
    private final int budget;
    private final AtomicLong simulations = new AtomicLong();

    public CodeMutationCampaign(int trials, int budget) {
        this.trials = trials;
        this.budget = budget;
    }

    public CodeMutationReport run(BiConsumer<String, Double> progress) {
        long start = System.nanoTime();
        List<CodeMutant> mutants = CodeMutator.all();
        Map<String, MutantResult> results = new ConcurrentHashMap<>();
        Map<String, Boolean> crashes = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();

        mutants.parallelStream().forEach(m -> {
            List<Trial> guided = trialsFor(m, Strategy.SEARCH);
            List<Trial> random = trialsFor(m, Strategy.RANDOM);
            boolean detected = isDetected(guided);
            SurvivorKind kind = detected ? null : classify(m, guided);
            String finding = guided.stream().filter(t -> t.finding() != null).map(Trial::finding).findFirst().orElse(null);
            double mean = guided.stream().filter(t -> t.firstDetection() > 0).mapToInt(Trial::firstDetection).average().orElse(Double.NaN);
            int detectedTrials = (int) guided.stream().filter(t -> t.firstDetection() > 0).count();
            results.put(m.id(), new MutantResult(m.id(), m.method(), m.line(), m.operator(), m.description(), m.sourceLine(),
                    detected, detectedTrials, Double.isNaN(mean) ? null : Math.round(mean * 10.0) / 10.0, isDetected(random), finding, kind));
            crashes.put(m.id(), guided.stream().anyMatch(Trial::crashed));
            progress.accept("Testing mutants of the braking code", (double) done.incrementAndGet() / mutants.size());
        });

        List<MutantResult> ordered = mutants.stream().map(m -> results.get(m.id())).toList();
        int detected = (int) ordered.stream().filter(MutantResult::detected).count();
        int randomDetected = (int) ordered.stream().filter(MutantResult::detectedByRandom).count();
        int noChange = (int) ordered.stream().filter(r -> r.survivorKind() == SurvivorKind.NO_BEHAVIOUR_CHANGE).count();
        int crashing = (int) crashes.values().stream().filter(Boolean::booleanValue).count();

        List<OperatorScore> byOperator = new ArrayList<>();
        for (String operator : ordered.stream().map(MutantResult::operator).distinct().toList()) {
            List<MutantResult> members = ordered.stream().filter(r -> r.operator().equals(operator)).toList();
            byOperator.add(new OperatorScore(operator, Score.of((int) members.stream().filter(MutantResult::detected).count(), members.size())));
        }

        return new CodeMutationReport(mutants.size(), trials, budget, Score.of(detected, mutants.size()), Score.of(randomDetected, mutants.size()),
                Score.of(detected, mutants.size() - noChange), noChange, crashing, byOperator, ordered, simulations.get(),
                (System.nanoTime() - start) / 1_000_000);
    }

    private List<Trial> trialsFor(CodeMutant m, Strategy strategy) {
        return IntStream.range(0, trials)
                .mapToObj(t -> trial(m, strategy, budget, 5000L * (t + 1) + m.id().hashCode()))
                .toList();
    }

    private Trial trial(CodeMutant m, Strategy strategy, int scenarios, long seed) {
        Evaluator evaluator = new Evaluator(Firmware.approved(), m.firmware(), ChangeManifest.NONE);
        // Settings are unchanged here, so a mutant gives the search no hint about what changed.
        ScenarioSearch search = new ScenarioSearch(evaluator, List.of());
        double[] maxChange = {0};
        boolean[] crashed = {false};
        ScenarioSearch.Outcome outcome = search.run(new ScenarioSearch.Config(strategy, scenarios, seed, true, List.of()),
                (e, done, coverage) -> {
                    maxChange[0] = Math.max(maxChange[0], MutationCampaign.behaviourChange(e.v1(), e.v2()));
                    crashed[0] |= e.v2().controllerCrashed();
                    return true;
                });
        simulations.addAndGet(outcome.simulations());
        if (outcome.firstDetectionAt() < 0) {
            return new Trial(-1, null, crashed[0], maxChange[0]);
        }
        Evaluation first = outcome.detections().get(0);
        return new Trial(outcome.firstDetectionAt(), first.findings().get(0).type().name(), crashed[0], maxChange[0]);
    }

    private SurvivorKind classify(CodeMutant m, List<Trial> guided) {
        Trial deep = trial(m, Strategy.SEARCH, budget * DEEP_RECHECK_MULTIPLIER, 31L + m.id().hashCode());
        boolean changed = deep.maxBehaviourChange() > SAME_BEHAVIOUR
                || guided.stream().anyMatch(t -> t.maxBehaviourChange() > SAME_BEHAVIOUR);
        return changed ? SurvivorKind.WITHIN_TOLERANCE : SurvivorKind.NO_BEHAVIOUR_CHANGE;
    }

    private boolean isDetected(List<Trial> list) {
        return list.stream().filter(t -> t.firstDetection() > 0).count() * 2 > list.size();
    }
}
