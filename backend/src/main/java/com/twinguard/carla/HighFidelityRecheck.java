package com.twinguard.carla;

import com.twinguard.gate.GateReport;
import com.twinguard.gate.ReleaseGate;
import com.twinguard.oracle.ChangeManifest;
import com.twinguard.oracle.Evaluation;
import com.twinguard.oracle.Evaluator;
import com.twinguard.oracle.Finding;
import com.twinguard.sim.ClosedLoopSimulator;
import com.twinguard.sim.Scenario;
import com.twinguard.sim.VehicleSimulator;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays the minimal failing scenarios on CARLA. CARLA is far too slow to run every scenario,
 * so the fast simulator finds the failures, and this step tests whether they hold up.
 *
 * Each scenario also runs v1 against an identical copy of v1. CARLA isn't perfectly repeatable, so
 * any finding from that control shows differences that come from CARLA, not from the release.
 */
public final class HighFidelityRecheck {

    private static final String TEST_DOUBLE = "test-double";
    /** The range where carla_bridge/calibrate.py matched stopping distance to within about 12%. */
    private static final double CALIBRATED_MIN_FRICTION = 0.2;
    private static final double CALIBRATED_MAX_FRICTION = 0.9;

    private HighFidelityRecheck() {
    }

    public static RecheckReport run(BridgeClient bridge, ReleaseGate.Target target, GateReport report) {
        long start = System.nanoTime();
        BridgeClient.Info info = bridge.ping();
        VehicleSimulator carla = new ClosedLoopSimulator(() -> new CarlaPlant(bridge));
        Evaluator evaluator = new Evaluator(target.baseline(), target.firmware(), target.manifest(), carla);
        Evaluator control = new Evaluator(target.baseline(), target.baseline(), ChangeManifest.NONE, carla);

        List<RecheckReport.Item> items = new ArrayList<>();
        for (GateReport.Counterexample c : report.counterexamples()) {
            Evaluation e = evaluator.evaluate(c.scenario());
            List<String> seen = e.findings().stream().map(f -> f.type().name()).toList();
            List<String> controlFindings = control.evaluate(c.scenario()).findings().stream().map(f -> f.type().name()).toList();
            boolean same = e.findings().stream().map(Finding::type).anyMatch(t -> t == c.primary().type());
            boolean controlToo = controlFindings.contains(c.primary().type().name());
            items.add(new RecheckReport.Item(c.primary().type().name(), c.scenario(), same && !controlToo, same && controlToo,
                    isCalibrated(c.scenario()), seen, controlFindings, GateReport.Metrics.of(e.v1()), GateReport.Metrics.of(e.v2())));
        }
        int reproduced = (int) items.stream().filter(RecheckReport.Item::reproduced).count();
        int inconclusive = (int) items.stream().filter(RecheckReport.Item::inconclusive).count();
        int within = (int) items.stream().filter(RecheckReport.Item::withinCalibration).count();
        int reproducedWithin = (int) items.stream().filter(i -> i.reproduced() && i.withinCalibration()).count();
        int falseAlarms = (int) items.stream().filter(i -> !i.controlFindings().isEmpty()).count();
        return new RecheckReport(info.backend(), info.map(), info.version(), TEST_DOUBLE.equals(info.backend()), info.calibrated(),
                items.size(), reproduced, inconclusive, reproducedWithin, within, falseAlarms, items, (System.nanoTime() - start) / 1_000_000);
    }

    private static boolean isCalibrated(Scenario s) {
        return s.friction() >= CALIBRATED_MIN_FRICTION && s.friction() <= CALIBRATED_MAX_FRICTION;
    }
}
