package com.twinguard.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MutationCampaignTest {

    @Test
    void wilsonIntervalMatchesAKnownValue() {
        CampaignReport.Score s = CampaignReport.Score.of(47, 50);
        assertEquals(94.0, s.percent(), 0.01);
        assertEquals(83.8, s.lower95(), 0.2);
    }

    @Test
    void campaignMeasuresTheTestProcess() {
        CampaignReport r = new MutationCampaign(MutationCampaign.DEFAULT_TRIALS, MutationCampaign.DEFAULT_BUDGET)
                .run((msg, p) -> { });

        System.out.printf("OVERALL %s%nDEV %s%nHOLDOUT %s%nHOLDOUT_BEFORE %s%nOVERALL_BEFORE %s%n",
                r.overall(), r.development(), r.holdout(), r.holdoutBeforeHardening(), r.overallBeforeHardening());
        System.out.printf("CORPUS +%d %s%n", r.corpusScenariosAdded(), r.hardenedFaults());
        r.byClass().forEach(c -> System.out.printf("CLASS %-28s %s%n", c.label(), c.score()));
        r.curve().forEach(p -> System.out.printf("CURVE %4d random=%5.1f search=%5.1f aware=%5.1f%n",
                p.scenarios(), p.random(), p.search(), p.changeAware()));
        r.holdoutCurve().forEach(p -> System.out.printf("CURVE_HOLDOUT %4d random=%5.1f search=%5.1f aware=%5.1f%n",
                p.scenarios(), p.random(), p.search(), p.changeAware()));
        System.out.printf("OVERALL_EXCL_NO_CHANGE %s (no-change faults %d)%n",
                r.excludingNoBehaviourChange(), r.noBehaviourChangeFaults());
        r.faults().stream().filter(f -> !f.detected())
                .forEach(f -> System.out.printf("SURVIVOR %-26s %-20s holdout=%s%n", f.id(), f.survivorKind(), f.holdout()));
        r.controls().forEach(c -> System.out.println("CONTROL " + c));
        System.out.printf("RUNTIME %d sims, %d ms, %.0f sims/s%n", r.simulations(), r.computeMs(), r.simulationsPerSecond());

        assertTrue(r.totalFaults() >= 50);
        assertTrue(r.overall().percent() > 0);
        r.controls().forEach(c -> assertTrue(c.passed(), c.toString()));
    }
}
