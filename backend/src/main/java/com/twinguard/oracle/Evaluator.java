package com.twinguard.oracle;

import com.twinguard.sim.Firmware;
import com.twinguard.sim.RunResult;
import com.twinguard.sim.Scenario;
import com.twinguard.sim.Simulator;
import com.twinguard.sim.VehicleSimulator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs v1 and the candidate side by side on identical inputs. A finding only counts when it
 * repeats on at least 2 of 3 noise seeds, so a single unlucky noise sample cannot block a release.
 */
public final class Evaluator {

    private static final int CONFIRMATION_SEEDS = 3;
    private static final int REQUIRED_CONFIRMATIONS = 2;
    private static final long SEED_STEP = 7_919;

    private final Firmware baseline;
    private final Firmware candidate;
    private final ChangeManifest manifest;
    private final VehicleSimulator simulator;

    public Evaluator(Firmware baseline, Firmware candidate, ChangeManifest manifest) {
        this(baseline, candidate, manifest, Simulator.FAST);
    }

    public Evaluator(Firmware baseline, Firmware candidate, ChangeManifest manifest, VehicleSimulator simulator) {
        this.baseline = baseline;
        this.candidate = candidate;
        this.manifest = manifest;
        this.simulator = simulator;
    }

    public Evaluation evaluate(Scenario s) {
        RunResult v1 = simulator.run(baseline, s, false);
        RunResult v2 = simulator.run(candidate, s, false);
        int simulations = 2;

        List<Finding> first = Oracle.judge(s, v1, v2, manifest);
        List<Finding> confirmed = List.of();

        if (!first.isEmpty()) {
            Map<Finding.Type, Integer> votes = new EnumMap<>(Finding.Type.class);
            first.forEach(f -> votes.merge(f.type(), 1, Integer::sum));
            for (int i = 1; i < CONFIRMATION_SEEDS; i++) {
                Scenario repeat = s.withSeed(s.seed() + i * SEED_STEP);
                RunResult r1 = simulator.run(baseline, repeat, false);
                RunResult r2 = simulator.run(candidate, repeat, false);
                simulations += 2;
                Oracle.judge(repeat, r1, r2, manifest).forEach(f -> votes.merge(f.type(), 1, Integer::sum));
            }
            List<Finding> kept = new ArrayList<>();
            for (Finding f : first) {
                if (votes.get(f.type()) >= REQUIRED_CONFIRMATIONS) {
                    kept.add(f);
                }
            }
            confirmed = kept;
        }

        return new Evaluation(s, v1, v2, confirmed, divergence(s, v1, v2), criticality(s, v1), simulations);
    }

    /** How differently the two versions behaved. This gives the search a gradient to follow. */
    private static double divergence(Scenario s, RunResult v1, RunResult v2) {
        double score = 0;
        if (v1.braked() != v2.braked()) {
            score += 5;
        } else if (v1.braked()) {
            score += 2 * Math.abs(v2.brakeOnsetS() - v1.brakeOnsetS());
        }
        if (s.type() == Scenario.Type.OBSTACLE) {
            score += Math.abs(v2.minGapM() - v1.minGapM()) / 5;
            score += Math.abs(v2.peakDecel() - v1.peakDecel()) / 5;
            score += Math.abs(v2.impactKph() - v1.impactKph()) / 10;
            if (v1.collision() != v2.collision()) {
                score += 5;
            }
        }
        score += Math.abs(v2.maxSensorAgeMs() - v1.maxSensorAgeMs()) / 200;
        return score;
    }

    /** How close to a crash the approved version came: 1 means touching, near 0 means comfortable. */
    private static double criticality(Scenario s, RunResult v1) {
        if (s.type() == Scenario.Type.GHOST) {
            return 0.2;
        }
        return v1.collision() ? 0.5 : 1.0 / (1.0 + v1.minGapM());
    }
}
