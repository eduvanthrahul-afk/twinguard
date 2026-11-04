package com.twinguard.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.twinguard.mutation.CodeMutator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FeatureCampaignTest {

    static java.util.List<VehicleFeature> features() {
        return Features.all();
    }

    @ParameterizedTest
    @MethodSource("features")
    void campaignScoresDesignedFaultsAndRealCodeMutants(VehicleFeature f) {
        FeatureCampaign.Report r = new FeatureCampaign(f).run((phase, progress) -> { });
        System.out.printf("CAMP %-11s faults %d/%d (random %d) | mutants %d/%d (%.1f%%, random %d, excl no-change %d/%d, no change %d) | false alarms %d in %d | %d sims %.1f s%n",
                f.id(), r.faults().detected(), r.faults().total(), r.faultsRandom().detected(), r.mutants().detected(), r.mutants().total(), r.mutants().percent(),
                r.mutantsRandom().detected(), r.excludingNoChange().detected(), r.excludingNoChange().total(), r.noChangeMutants(), r.falseAlarms(), r.controlScenarios(),
                r.simulations(), r.computeMs() / 1000.0);
        r.mutantResults().stream().filter(m -> !m.detected() && m.survivorKind() == com.twinguard.campaign.CampaignReport.SurvivorKind.WITHIN_TOLERANCE)
                .limit(6).forEach(m -> System.out.printf("   TOL %s L%d %s | %s%n", m.id(), m.line(), m.description(), m.sourceLine()));
        assertTrue(r.mutants().total() >= 20, "only " + r.mutants().total());
        assertTrue(r.mutants().detected() > 0);
        assertEquals(0, r.falseAlarms(), "Safe software must not raise alarms");
    }

    @ParameterizedTest
    @MethodSource("features")
    void mutantsAreTracedToSourceLines(VehicleFeature f) {
        for (CodeMutator.Generated m : FeatureMutants.of(f)) {
            assertTrue(m.line() > 0, m.id());
            assertTrue(!m.sourceLine().isBlank(), m.id() + " has no source line");
        }
    }
}
