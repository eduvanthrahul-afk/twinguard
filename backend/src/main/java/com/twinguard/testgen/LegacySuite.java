package com.twinguard.testgen;

import com.twinguard.sim.Firmware;
import com.twinguard.sim.RunResult;
import com.twinguard.sim.Scenario;
import com.twinguard.sim.Simulator;
import java.util.ArrayList;
import java.util.List;

/**
 * A conventional fixed regression suite: normal roads, light infotainment use, generous distances,
 * and a pass/fail check on the candidate alone. It is the "all tests passed" baseline.
 */
public final class LegacySuite {

    public record Result(int passed, int total) {
    }

    private static final double[] SPEEDS = {30, 50, 70, 90};
    private static final double[] DISTANCE_FACTORS = {1.8, 2.5, 3.5};
    private static final double[] FRICTIONS = {0.8, 0.6};
    private static final double[] LOADS = {0.1, 0.3};

    private LegacySuite() {
    }

    public static List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();
        long seed = 100;
        for (double kph : SPEEDS) {
            double v = kph / 3.6;
            for (double mu : FRICTIONS) {
                double stop = v * v / (2 * mu * Simulator.G);
                for (double factor : DISTANCE_FACTORS) {
                    for (double load : LOADS) {
                        double distance = Math.min(Scenario.MAX_DIST, stop * factor + 10);
                        out.add(new Scenario(Scenario.Type.OBSTACLE, kph, distance, mu, 0.2, load, seed++));
                    }
                }
            }
        }
        return out;
    }

    public static Result run(Firmware candidate) {
        List<Scenario> all = scenarios();
        int passed = 0;
        for (Scenario s : all) {
            RunResult r = Simulator.run(candidate, s, false);
            if (r.braked() && !r.collision()) {
                passed++;
            }
        }
        return new Result(passed, all.size());
    }
}
