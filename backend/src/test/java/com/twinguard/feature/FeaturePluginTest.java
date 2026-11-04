package com.twinguard.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.twinguard.gate.GateReport;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every feature must pass these. A new plugin only needs adding to the list. */
class FeaturePluginTest {

    static List<VehicleFeature> features() {
        return List.of(new BatteryFeature(), new CruiseFeature(), new LaneFeature(), new PedestrianFeature());
    }

    private static FeatureEngine selfCompare(VehicleFeature f) {
        Supplier<FeatureController> base = f.source(Map.of());
        return new FeatureEngine(f, base, base, Set.of());
    }

    @ParameterizedTest
    @MethodSource("features")
    void identicalSoftwareNeverRaisesADifference(VehicleFeature f) {
        FeatureEngine engine = selfCompare(f);
        int detections = 0;
        for (long seed = 1; seed <= 8; seed++) {
            for (FeatureEngine.Strategy strategy : FeatureEngine.Strategy.values()) {
                FeatureEngine.Outcome o = engine.search(strategy, 1500, seed * 1009, false, null);
                detections += o.detections().size();
                o.detections().stream().limit(2).forEach(e -> System.out.println("   " + f.id() + ": " + e.findings().get(0).message() + " <- " + java.util.Arrays.toString(e.scenario())));
            }
        }
        System.out.printf("SELF %-10s detections %d in %d scenarios%n", f.id(), detections, 8 * 2 * 1500);
        assertEquals(0, detections, "v1 must meet its own safety rules, or the rules or model are wrong");
    }

    @ParameterizedTest
    @MethodSource("features")
    void conventionalSuitePassesOnApprovedSoftware(VehicleFeature f) {
        FeatureEngine engine = selfCompare(f);
        int passed = 0;
        List<double[]> suite = f.conventionalScenarios();
        for (double[] s : suite) {
            if (f.conventionalPass(s, engine.run(f.source(Map.of()), s, 100, false))) {
                passed++;
            }
        }
        System.out.printf("SUITE %-10s %d of %d%n", f.id(), passed, suite.size());
        assertTrue(suite.size() >= 10);
        assertEquals(suite.size(), passed);
    }

    @ParameterizedTest
    @MethodSource("features")
    void simulationIsRepeatable(VehicleFeature f) {
        FeatureEngine engine = selfCompare(f);
        double[] s = f.uniform(new Random(9));
        assertEquals(engine.run(f.source(Map.of()), s, 5, false).metrics(), engine.run(f.source(Map.of()), s, 5, false).metrics());
    }

    @ParameterizedTest
    @MethodSource("features")
    void releasesGetAVerdict(VehicleFeature f) {
        Supplier<FeatureController> base = f.source(Map.of());
        List<String> wrong = new java.util.ArrayList<>();
        for (VehicleFeature.Release r : f.releases()) {
            FeatureReport report = FeatureGate.run(f, r.id(), r.title(), List.copyOf(r.overrides().keySet()), base, f.source(r.overrides()), r.declaredChanges(), 1500, 11, null);
            System.out.printf("REL %-10s %-22s %-8s conventional %d/%d fails %d cov %d/%d %d ms %s%n", f.id(), r.id(), report.verdict(), report.conventionalPassed(),
                    report.conventionalTotal(), report.failingScenarios(), report.coveredCells(), report.totalCells(), report.computeMs(), report.findingCounts());
            report.counterexamples().forEach(c -> System.out.println("     " + c.primary().type() + ": " + c.primary().message() + " <- " + c.scenario()));
            GateReport.Verdict expected = r.declaredChanges().isEmpty() ? GateReport.Verdict.BLOCK : GateReport.Verdict.APPROVE;
            if (report.verdict() != expected) {
                wrong.add(r.id() + " was " + report.verdict());
            }
        }
        assertEquals(List.of(), wrong);
    }

    /** A safe release flagged at random would embarrass the demo, and buggy ones must never slip through. */
    @ParameterizedTest
    @MethodSource("features")
    void verdictsHoldAcrossSeeds(VehicleFeature f) {
        Supplier<FeatureController> base = f.source(Map.of());
        int wrong = 0;
        for (VehicleFeature.Release r : f.releases()) {
            GateReport.Verdict expected = r.declaredChanges().isEmpty() ? GateReport.Verdict.BLOCK : GateReport.Verdict.APPROVE;
            for (long seed = 1; seed <= 25; seed++) {
                FeatureReport report = FeatureGate.run(f, r.id(), r.title(), List.copyOf(r.overrides().keySet()), base, f.source(r.overrides()), r.declaredChanges(), 1500, seed * 7_000_003L, null);
                if (report.verdict() != expected) {
                    wrong++;
                    System.out.println("   WRONG " + r.id() + " seed " + seed + " " + report.verdict() + " " + report.findingCounts());
                }
            }
        }
        System.out.printf("SEEDS %-10s wrong verdicts %d of %d%n", f.id(), wrong, f.releases().size() * 25);
        assertEquals(0, wrong);
    }

    @ParameterizedTest
    @MethodSource("features")
    void generatedFaultsAreValid(VehicleFeature f) {
        Supplier<FeatureController> base = f.source(Map.of());
        int caught = 0;
        for (VehicleFeature.SettingFault fault : f.faults()) {
            FeatureEngine engine = new FeatureEngine(f, base, f.source(fault.overrides()), Set.of());
            FeatureEngine.Outcome o = engine.search(FeatureEngine.Strategy.SEARCH, 400, 21, true, null);
            if (o.firstDetectionAt() > 0) {
                caught++;
            } else {
                System.out.printf("MISS %-10s %-22s maxChange %.4f%n", f.id(), fault.id(), o.maxChange());
            }
        }
        System.out.printf("FAULTS %-10s caught %d of %d%n", f.id(), caught, f.faults().size());
        assertTrue(f.faults().size() >= 15);
    }
}
