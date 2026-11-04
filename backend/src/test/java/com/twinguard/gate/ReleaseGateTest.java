package com.twinguard.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.twinguard.catalog.Release;
import com.twinguard.catalog.ReleaseCatalog;
import com.twinguard.testgen.ScenarioSearch;
import org.junit.jupiter.api.Test;

class ReleaseGateTest {

    private static GateReport gate(String id) {
        Release r = ReleaseCatalog.find(id).orElseThrow();
        ReleaseGate.Target t = new ReleaseGate.Target(r.id(), r.title(), "release", r.firmware(), r.manifest());
        GateReport report = ReleaseGate.run(t, 1500, ScenarioSearch.Strategy.CHANGE_AWARE, 11, null);
        System.out.printf("%-16s %-8s legacy %d/%d fails %d cov %.1f%% ms %d findings %s%n",
                id, report.verdict(), report.legacy().passed(), report.legacy().total(),
                report.failingScenarios(), report.coveragePercent(), report.computeMs(), report.findingCounts());
        report.counterexamples().forEach(c -> System.out.println("    " + c.primary().type() + " "
                + c.primary().message() + " <- " + c.scenario()));
        return report;
    }

    /** The dashboard uses a new random seed on every run, so the demo verdicts must not depend on the seed. */
    @Test
    void demoVerdictsHoldAcrossRandomSeeds() {
        ReleaseGate.Target safe = target("v2.1-comfort");
        ReleaseGate.Target media = target("v2.2-media");
        ReleaseGate.Target radar = target("v2.4-fast-radar");
        int safeNotApproved = 0;
        int bugsNotBlocked = 0;
        // These seeds were not used when the false alarms were diagnosed.
        for (long seed = 1; seed <= 100; seed++) {
            long s = seed * 7_000_003L;
            GateReport safeRun = ReleaseGate.run(safe, 1500, ScenarioSearch.Strategy.CHANGE_AWARE, s, null);
            if (safeRun.verdict() != GateReport.Verdict.APPROVE) {
                safeNotApproved++;
                System.out.println("SEED_FLAGGED " + seed + " " + safeRun.findingCounts());
            }
            if (seed <= 15) {
                if (ReleaseGate.run(media, 1500, ScenarioSearch.Strategy.CHANGE_AWARE, s, null).verdict() != GateReport.Verdict.BLOCK) {
                    bugsNotBlocked++;
                }
                if (ReleaseGate.run(radar, 1500, ScenarioSearch.Strategy.CHANGE_AWARE, s, null).verdict() != GateReport.Verdict.BLOCK) {
                    bugsNotBlocked++;
                }
            }
        }
        System.out.printf("SEEDS safeNotApproved=%d of 100 seeds, bugsNotBlocked=%d of 30 runs%n", safeNotApproved, bugsNotBlocked);
        assertEquals(0, safeNotApproved, "The safe release must not be flagged on any seed");
        assertEquals(0, bugsNotBlocked, "The buggy releases must be blocked on every seed");
    }

    private static ReleaseGate.Target target(String id) {
        Release r = ReleaseCatalog.find(id).orElseThrow();
        return new ReleaseGate.Target(r.id(), r.title(), "release", r.firmware(), r.manifest());
    }

    @Test
    void mediaUpdatePassesLegacyTestsButIsBlocked() {
        GateReport r = gate("v2.2-media");
        assertEquals(r.legacy().total(), r.legacy().passed(), "The legacy suite must miss this bug");
        assertEquals(GateReport.Verdict.BLOCK, r.verdict());
        assertFalse(r.counterexamples().isEmpty());
        assertFalse(r.counterexamples().get(0).v2Trace().isEmpty());
    }

    @Test
    void declaredComfortChangeIsApproved() {
        assertEquals(GateReport.Verdict.APPROVE, gate("v2.1-comfort").verdict());
    }

    @Test
    void reducedStoppingMarginIsBlocked() {
        assertEquals(GateReport.Verdict.BLOCK, gate("v2.3-nuisance").verdict());
    }

    @Test
    void removedRadarFilteringIsBlockedAsPhantomBraking() {
        GateReport r = gate("v2.4-fast-radar");
        assertEquals(GateReport.Verdict.BLOCK, r.verdict());
        assertTrue(r.findingCounts().containsKey(com.twinguard.oracle.Finding.Type.PHANTOM_BRAKE));
    }
}
