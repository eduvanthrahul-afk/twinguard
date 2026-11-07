package com.twinguard.calibration;

import com.twinguard.feature.Features;
import com.twinguard.feature.VehicleFeature;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Re-tunes the v1 battery management settings for the calibrated cell, the way a BMS engineer would after
 * receiving the cell data: the measured resistance doubles near freezing, so the taper loop has to react faster.
 *
 * It searches the taper gain and current ramp, and keeps the gentlest pair under which v1 never breaks the
 * 4.25 V rule and still passes its conventional suite, across random scenarios over the whole parameter range.
 */
public final class BatteryTune {

    private static final int SCENARIOS = 3_000;
    private static final double LIMIT = 4.25;

    private BatteryTune() {
    }

    public static void main(String[] args) {
        VehicleFeature battery = Features.all().stream().filter(f -> f.id().equals("battery")).findFirst().orElseThrow();
        double[][] scenarios = scenarios(battery);
        System.out.printf("%-10s %-8s %-12s %-10s%n", "taperGain", "ramp", "worst V", "conv pass");
        for (double gain : new double[] {0.2, 0.5, 1.0, 2.0, 4.0}) {
            for (double ramp : new double[] {0.05, 0.02, 0.01, 0.005}) {
                Map<String, Double> s = new LinkedHashMap<>(battery.defaultSettings());
                s.put("taperGain", gain);
                s.put("ramp", ramp);
                double worst = 0;
                for (int i = 0; i < scenarios.length; i++) {
                    worst = Math.max(worst, battery.simulate(battery.logic(s), scenarios[i], i, false).metrics().get("peakVoltage"));
                }
                long pass = battery.conventionalScenarios().stream()
                        .filter(c -> battery.conventionalPass(c, battery.simulate(battery.logic(s), c, 1, false))).count();
                System.out.printf("%-10.2f %-8.3f %-12.4f %d/%d %s%n", gain, ramp, worst, pass, battery.conventionalScenarios().size(),
                        worst <= LIMIT && pass == battery.conventionalScenarios().size() ? "SAFE" : "");
            }
        }
    }

    private static double[][] scenarios(VehicleFeature f) {
        Random rng = new Random(42);
        double[][] out = new double[SCENARIOS][];
        for (int i = 0; i < SCENARIOS; i++) {
            double[] s = new double[f.params().size()];
            for (int k = 0; k < s.length; k++) {
                VehicleFeature.Param p = f.params().get(k);
                s[k] = p.min() + rng.nextDouble() * (p.max() - p.min());
            }
            out[i] = s;
        }
        return out;
    }
}
