package com.twinguard.fmu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.twinguard.feature.BatteryFeature;
import com.twinguard.feature.FeatureController;
import com.twinguard.feature.FeatureGate;
import com.twinguard.feature.FeatureReport;
import com.twinguard.feature.VehicleFeature;
import com.twinguard.gate.GateReport;
import com.twinguard.gate.ReleaseGate;
import com.twinguard.oracle.ChangeManifest;
import com.twinguard.oracle.Evaluation;
import com.twinguard.oracle.Evaluator;
import com.twinguard.sim.Firmware;
import com.twinguard.sim.RunResult;
import com.twinguard.sim.Scenario;
import com.twinguard.sim.Simulator;
import com.twinguard.testgen.ScenarioSearch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Runs real native code from compiled C. These need fmus/build.ps1 to have been run, and skip otherwise. */
class FmuTest {

    private static final Path DIR = Path.of("..", "fmus");

    private static FmuFile load(String name) {
        Path file = DIR.resolve(name + ".fmu");
        assumeTrue(Files.exists(file), "Run fmus/build.ps1 first");
        assumeTrue(com.sun.jna.Platform.isWindows(), "These FMUs only contain Windows binaries");
        try {
            return FmuFile.load(file);
        } catch (FmuException e) {
            // Skipped, not passed: this PC's security policy decides, and these tests don't work around it.
            assumeTrue(!e.blockedByPolicy(), "Blocked by this PC's Application Control policy: " + name);
            throw e;
        }
    }

    /** The general adapter has to pass values through native code exactly as the brake adapter does. */
    @Test
    void generalAdapterRunsNativeCodeCorrectly() {
        FmuFile fmu = load("brake_v1_supplier");
        VehicleFeature feature = mock(VehicleFeature.class);
        when(feature.inputNames()).thenReturn(FmuAdapters.BRAKE_INPUTS);
        when(feature.outputNames()).thenReturn(FmuAdapters.BRAKE_OUTPUTS);
        when(feature.stepSeconds()).thenReturn(0.01);
        FeatureController controller = FmuAdapters.forFeature(fmu, feature).get();

        // A close target, confirmed over three radar samples, at 72 km/h on a dry road.
        for (int tick = 0; tick < 3; tick++) {
            controller.step(new double[] {1, 1, 10, tick, tick, 20, 0.8});
        }
        double[] out = controller.step(new double[] {0, 1, 10, 2, 3, 20, 0.8});
        assertEquals(1.0, out[0], 1e-12, "A close confirmed target must request full braking");

        FeatureController fresh = FmuAdapters.forFeature(fmu, feature).get();
        assertEquals(0.0, fresh.step(new double[] {0, 0, 0, 0, 0, 20, 0.8})[0], 1e-12, "With no target it must not brake");
    }

    @Test
    void policyBlockProducesAClearMessage() {
        FmuException blocked = new FmuException("Windows blocked x.fmu", new UnsatisfiedLinkError(), true);
        assertTrue(blocked.blockedByPolicy());
    }

    @Test
    void cVersionOfApprovedBrakingBehavesExactlyLikeJava() {
        Firmware fmu = FmuAdapters.brakeFirmware(load("brake_v1_supplier"));
        Random r = new Random(4);
        int compared = 0;
        double maxOnset = 0;
        double maxGap = 0;
        for (int i = 0; i < 3000; i++) {
            Scenario s = new Scenario(r.nextDouble() < 0.15 ? Scenario.Type.GHOST : Scenario.Type.OBSTACLE,
                    20 + r.nextDouble() * 110, 10 + r.nextDouble() * 150, 0.2 + r.nextDouble() * 0.8, r.nextDouble() * 1.5, r.nextDouble(), r.nextLong());
            RunResult java = Simulator.run(Firmware.approved(), s, false);
            RunResult c = Simulator.run(fmu, s, false);
            assertEquals(java.braked(), c.braked(), s.toString());
            assertEquals(java.collision(), c.collision(), s.toString());
            if (java.braked()) {
                maxOnset = Math.max(maxOnset, Math.abs(java.brakeOnsetS() - c.brakeOnsetS()));
            }
            maxGap = Math.max(maxGap, Math.abs(java.minGapM() - c.minGapM()));
            compared++;
        }
        System.out.printf("FMU_XCHECK brake %d scenarios, max onset difference %.6f s, max gap difference %.6f m%n", compared, maxOnset, maxGap);
        assertEquals(0, maxOnset, 1e-9);
        assertEquals(0, maxGap, 1e-9);
    }

    @Test
    void cVersionOfApprovedBatteryBehavesExactlyLikeJava() {
        BatteryFeature feature = new BatteryFeature();
        FmuFile fmu = load("battery_v1_supplier");
        var engine = new com.twinguard.feature.FeatureEngine(feature, feature.source(Map.of()), FmuAdapters.forFeature(fmu, feature), Set.of());
        Random r = new Random(5);
        double max = 0;
        for (int i = 0; i < 300; i++) {
            max = Math.max(max, engine.evaluate(feature.uniform(r), r.nextLong()).divergence());
        }
        System.out.printf("FMU_XCHECK battery 300 scenarios, max divergence %.9f%n", max);
        assertEquals(0, max, 1e-9);
    }

    @Test
    void gateBlocksABuggyBrakeFmuThatItKnowsNothingAbout() {
        for (String name : List.of("brake_v2_margin_bug", "brake_v2_radar_bug")) {
            ReleaseGate.Target target = new ReleaseGate.Target(name, name, "fmu", FmuAdapters.brakeFirmware(load(name)), ChangeManifest.NONE,
                    FmuAdapters.brakeFirmware(load("brake_v1_supplier")));
            GateReport report = ReleaseGate.run(target, 600, ScenarioSearch.Strategy.SEARCH, 7, null);
            System.out.printf("FMU_GATE %-20s %s legacy %d/%d fails %d %d ms %s%n", name, report.verdict(), report.legacy().passed(), report.legacy().total(),
                    report.failingScenarios(), report.computeMs(), report.findingCounts());
            assertEquals(GateReport.Verdict.BLOCK, report.verdict());
            assertTrue(report.counterexamples().get(0).v2Trace().size() > 0);
        }
    }

    @Test
    void gateApprovesAnIdenticalBrakeFmu() {
        ReleaseGate.Target target = new ReleaseGate.Target("same", "same", "fmu", FmuAdapters.brakeFirmware(load("brake_v1_supplier")), ChangeManifest.NONE,
                FmuAdapters.brakeFirmware(load("brake_v1_supplier")));
        assertEquals(GateReport.Verdict.APPROVE, ReleaseGate.run(target, 300, ScenarioSearch.Strategy.SEARCH, 7, null).verdict());
    }

    @Test
    void gateJudgesABatteryFmu() {
        VehicleFeature f = new BatteryFeature();
        Supplier<FeatureController> base = FmuAdapters.forFeature(load("battery_v1_supplier"), f);
        Supplier<FeatureController> buggy = FmuAdapters.forFeature(load("battery_v2_hot_bug"), f);
        FeatureReport report = FeatureGate.run(f, "battery_v2_hot_bug", "Battery FMU", List.of(), base, buggy, Set.of(), 300, 3, null);
        System.out.printf("FMU_GATE battery %s fails %d %d ms %s%n", report.verdict(), report.failingScenarios(), report.computeMs(), report.findingCounts());
        assertEquals(GateReport.Verdict.BLOCK, report.verdict());
    }

    @Test
    void wrongFeatureAndBadFilesGiveClearErrors() throws IOException {
        FmuFile brake = load("brake_v1_supplier");
        FmuException wrong = assertThrows(FmuException.class, () -> FmuAdapters.forFeature(brake, new BatteryFeature()));
        assertTrue(wrong.getMessage().contains("missing"), wrong.getMessage());

        Path notAnFmu = Files.createTempFile("not-an-fmu", ".fmu");
        Files.writeString(notAnFmu, "hello");
        FmuException bad = assertThrows(FmuException.class, () -> FmuFile.describe(notAnFmu));
        assertTrue(bad.getMessage().contains("can't be read"), bad.getMessage());
        Files.delete(notAnFmu);
    }

    @Test
    void memoryIsReleasedAfterEachRun() {
        Firmware fmu = FmuAdapters.brakeFirmware(load("brake_v1_supplier"));
        Scenario s = new Scenario(Scenario.Type.OBSTACLE, 80, 90, 0.7, 0.2, 0.2, 42);
        for (int i = 0; i < 20000; i++) {
            Simulator.run(fmu, s, false);
        }
        System.out.println("FMU_MEMORY 20000 runs finished");
    }

    @Test
    void anEvaluatorCanCompareTwoFmus() {
        Firmware v1 = FmuAdapters.brakeFirmware(load("brake_v1_supplier"));
        Firmware v2 = FmuAdapters.brakeFirmware(load("brake_v2_margin_bug"));
        Evaluation e = new Evaluator(v1, v2, ChangeManifest.NONE).evaluate(new Scenario(Scenario.Type.OBSTACLE, 100, 100, 0.8, 0, 0, 1));
        assertTrue(e.divergence() > 0);
    }
}
