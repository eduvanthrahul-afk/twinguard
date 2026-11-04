package com.twinguard.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.twinguard.sim.Firmware;
import com.twinguard.sim.RunResult;
import com.twinguard.sim.Scenario;
import com.twinguard.sim.Simulator;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeMutationTest {

    private static final Scenario SCENARIO = new Scenario(Scenario.Type.OBSTACLE, 80, 90, 0.7, 0.2, 0.2, 42);

    @Test
    void generatesManyMutantsTracedToSourceLines() {
        List<CodeMutant> mutants = CodeMutator.all();
        assertTrue(mutants.size() >= 50, "only " + mutants.size());
        assertTrue(mutants.stream().allMatch(m -> m.line() > 0));
        assertTrue(mutants.stream().allMatch(m -> !m.sourceLine().isBlank()), "every mutant should show its source line");
        assertEquals(mutants.size(), mutants.stream().map(CodeMutant::id).distinct().count());
    }

    @Test
    void mutantsActuallyChangeBehaviourWithoutTouchingTheOriginal() {
        RunResult before = Simulator.run(Firmware.approved(), SCENARIO, false);
        long changed = CodeMutator.all().stream()
                .map(m -> Simulator.run(m.firmware(), SCENARIO, false))
                .filter(r -> r.minGapM() != before.minGapM() || r.collision() != before.collision() || r.controllerCrashed())
                .count();
        RunResult after = Simulator.run(Firmware.approved(), SCENARIO, false);
        assertTrue(changed >= 10, "only " + changed + " mutants changed this scenario");
        assertEquals(before.minGapM(), after.minGapM());
        assertEquals(before.brakeOnsetS(), after.brakeOnsetS());
    }

    @Test
    void aCrashingMutantIsReportedInsteadOfStoppingTheRun() {
        long crashed = CodeMutator.all().stream()
                .map(m -> Simulator.run(m.firmware(), SCENARIO, false))
                .filter(RunResult::controllerCrashed)
                .count();
        System.out.println("CRASHING_ON_ONE_SCENARIO " + crashed);
        assertFalse(Simulator.run(Firmware.approved(), SCENARIO, false).controllerCrashed());
    }

    @Test
    void campaignScoresTheGateOnCodeMutants() {
        CodeMutationReport r = new CodeMutationCampaign(CodeMutationCampaign.DEFAULT_TRIALS, CodeMutationCampaign.DEFAULT_BUDGET)
                .run((phase, progress) -> { });
        System.out.printf("MUT_OVERALL %s%nMUT_RANDOM %s%nMUT_EXCL %s (no change %d, crashing %d)%n",
                r.overall(), r.randomBaseline(), r.excludingNoBehaviourChange(), r.noBehaviourChangeMutants(), r.crashingMutants());
        r.byOperator().forEach(o -> System.out.printf("MUT_OP %-22s %s%n", o.operator(), o.score()));
        r.mutants().stream().filter(m -> !m.detected()).forEach(m -> System.out.printf("MUT_SURVIVOR %s %-20s L%d %-16s %s | %s%n",
                m.id(), m.survivorKind(), m.line(), m.method(), m.description(), m.sourceLine()));
        System.out.printf("MUT_RUNTIME %d sims %d ms%n", r.simulations(), r.computeMs());
        assertEquals(CodeMutator.all().size(), r.totalMutants());
        assertTrue(r.overall().detected() > 0);
    }
}
