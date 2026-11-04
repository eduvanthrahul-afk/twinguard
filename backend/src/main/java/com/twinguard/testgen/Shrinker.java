package com.twinguard.testgen;

import com.twinguard.oracle.Evaluation;
import com.twinguard.oracle.Evaluator;
import com.twinguard.oracle.Finding;
import com.twinguard.sim.Scenario;
import java.util.function.UnaryOperator;

/**
 * Reduces a failure to its simplest form: as little noise and load as possible, and round values,
 * while the same type of failure still reproduces. Engineers then see what actually causes it.
 */
public final class Shrinker {

    private static final int BISECT_STEPS = 6;

    private final Evaluator evaluator;

    public Shrinker(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    public Evaluation shrink(Evaluation failure) {
        Finding.Type target = failure.findings().get(0).type();
        Scenario s = failure.scenario();

        s = attempt(s, target, c -> copy(c, c.speedKph(), c.distanceM(), c.friction(), 0, c.infotainmentLoad()));
        s = bisectLoad(s, target);
        s = attempt(s, target, c -> copy(c, c.speedKph(), c.distanceM(), 0.8, c.noiseM(), c.infotainmentLoad()));
        s = attempt(s, target, c -> copy(c, Math.round(c.speedKph() / 5) * 5.0, c.distanceM(), c.friction(), c.noiseM(), c.infotainmentLoad()));
        s = attempt(s, target, c -> copy(c, c.speedKph(), Math.round(c.distanceM() / 5) * 5.0, c.friction(), c.noiseM(), c.infotainmentLoad()));
        s = attempt(s, target, c -> copy(c, c.speedKph(), c.distanceM(), Math.round(c.friction() * 20) / 20.0, c.noiseM(), c.infotainmentLoad()));
        s = attempt(s, target, c -> copy(c, c.speedKph(), c.distanceM(), c.friction(), c.noiseM(), Math.ceil(c.infotainmentLoad() * 20) / 20.0));
        return evaluator.evaluate(s);
    }

    private Scenario bisectLoad(Scenario s, Finding.Type target) {
        Scenario zero = copy(s, s.speedKph(), s.distanceM(), s.friction(), s.noiseM(), 0);
        if (stillFails(zero, target)) {
            return zero;
        }
        double failing = s.infotainmentLoad();
        double passing = 0;
        for (int i = 0; i < BISECT_STEPS; i++) {
            double mid = (failing + passing) / 2;
            if (stillFails(copy(s, s.speedKph(), s.distanceM(), s.friction(), s.noiseM(), mid), target)) {
                failing = mid;
            } else {
                passing = mid;
            }
        }
        return copy(s, s.speedKph(), s.distanceM(), s.friction(), s.noiseM(), failing);
    }

    private Scenario attempt(Scenario s, Finding.Type target, UnaryOperator<Scenario> change) {
        Scenario candidate = change.apply(s).clamped();
        return stillFails(candidate, target) ? candidate : s;
    }

    private boolean stillFails(Scenario s, Finding.Type target) {
        return evaluator.evaluate(s).findings().stream().anyMatch(f -> f.type() == target);
    }

    private static Scenario copy(Scenario s, double speed, double distance, double friction, double noise, double load) {
        return new Scenario(s.type(), speed, distance, friction, noise, load, s.seed());
    }
}
