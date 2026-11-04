package com.twinguard.testgen;

import com.twinguard.oracle.Evaluation;
import com.twinguard.oracle.Evaluator;
import com.twinguard.sim.Scenario;
import com.twinguard.sim.Simulator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates and prioritises scenarios. RANDOM is the honest baseline that the others must beat.
 * SEARCH favours near-collision boundaries and follows v1/v2 divergence.
 * CHANGE_AWARE also steers toward the conditions that the changed settings can influence.
 */
public final class ScenarioSearch {

    public enum Strategy { RANDOM, SEARCH, CHANGE_AWARE }

    public record Config(Strategy strategy, int budget, long rngSeed, boolean stopAtFirstDetection,
                         List<Scenario> corpus) {
    }

    public interface Listener {
        boolean onEvaluation(Evaluation evaluation, int done, Coverage coverage);
    }

    public record Outcome(List<Evaluation> detections, Coverage coverage, int evaluations, int simulations,
                          int firstDetectionAt) {
    }

    private final Evaluator evaluator;
    private final List<String> changedSettings;

    public ScenarioSearch(Evaluator evaluator, List<String> changedSettings) {
        this.evaluator = evaluator;
        this.changedSettings = changedSettings;
    }

    public Outcome run(Config config, Listener listener) {
        Random rng = new Random(config.rngSeed());
        Coverage coverage = new Coverage();
        List<Evaluation> detections = new ArrayList<>();
        int simulations = 0;
        int firstDetection = -1;
        int done = 0;

        while (done < config.budget()) {
            Scenario next = done < config.corpus().size()
                    ? config.corpus().get(done)
                    : generate(config.strategy(), rng, coverage);
            Evaluation e = evaluator.evaluate(next);
            done++;
            simulations += e.simulations();
            coverage.record(e);
            if (e.detected()) {
                detections.add(e);
                if (firstDetection < 0) {
                    firstDetection = done;
                }
            }
            if (listener != null && !listener.onEvaluation(e, done, coverage)) {
                break;
            }
            if (config.stopAtFirstDetection() && e.detected()) {
                break;
            }
        }
        return new Outcome(detections, coverage, done, simulations, firstDetection);
    }

    private Scenario generate(Strategy strategy, Random rng, Coverage coverage) {
        if (strategy == Strategy.RANDOM) {
            return uniform(rng);
        }
        double roll = rng.nextDouble();
        if (strategy == Strategy.CHANGE_AWARE && !changedSettings.isEmpty() && roll < 0.5) {
            return changeFocused(rng);
        }
        double r = rng.nextDouble();
        if (r < 0.1) {
            return ghost(rng);
        }
        if (r < 0.3) {
            return unexplored(rng, coverage);
        }
        if (r < 0.65) {
            return nearBoundary(rng);
        }
        Scenario elite = coverage.pickElite(rng);
        return elite == null ? nearBoundary(rng) : perturb(elite, rng);
    }

    private static Scenario uniform(Random rng) {
        return new Scenario(
                rng.nextDouble() < 0.1 ? Scenario.Type.GHOST : Scenario.Type.OBSTACLE,
                between(rng, Scenario.MIN_SPEED, Scenario.MAX_SPEED),
                between(rng, Scenario.MIN_DIST, Scenario.MAX_DIST),
                between(rng, Scenario.MIN_FRICTION, Scenario.MAX_FRICTION),
                between(rng, 0, Scenario.MAX_NOISE),
                rng.nextDouble(),
                rng.nextLong());
    }

    private static Scenario unexplored(Random rng, Coverage coverage) {
        Scenario candidate = uniform(rng);
        for (int i = 0; i < 8 && coverage.isCovered(candidate); i++) {
            candidate = uniform(rng);
        }
        return candidate;
    }

    /** Places the obstacle just around where the car must start braking to avoid it. */
    private static Scenario nearBoundary(Random rng) {
        // Redraw when the car could not stop within the simulated range: such a case cannot show a regression.
        Scenario base = uniform(rng);
        double v = base.speedMs();
        double stop = v * v / (2 * base.friction() * Simulator.G);
        for (int i = 0; i < 10 && stop * 1.3 + v > Scenario.MAX_DIST; i++) {
            base = uniform(rng);
            v = base.speedMs();
            stop = v * v / (2 * base.friction() * Simulator.G);
        }
        double distance = rng.nextBoolean()
                ? stop * between(rng, 1.0, 1.7) + v * between(rng, 0.2, 0.8)
                : v * between(rng, 0.8, 3.0);
        return new Scenario(Scenario.Type.OBSTACLE, base.speedKph(), distance, base.friction(),
                base.noiseM(), base.infotainmentLoad(), base.seed()).clamped();
    }

    private static Scenario perturb(Scenario s, Random rng) {
        return new Scenario(s.type(),
                s.speedKph() + rng.nextGaussian() * 8,
                s.distanceM() + rng.nextGaussian() * 10,
                s.friction() + rng.nextGaussian() * 0.08,
                s.noiseM() + rng.nextGaussian() * 0.2,
                s.infotainmentLoad() + rng.nextGaussian() * 0.1,
                rng.nextLong()).clamped();
    }

    /** Change impact: maps each changed setting to the road conditions where it matters. */
    private Scenario changeFocused(Random rng) {
        String setting = changedSettings.get(rng.nextInt(changedSettings.size()));
        Scenario s = nearBoundary(rng);
        return switch (setting) {
            case "infotainmentPriority", "infotainmentRate", "gatewayCapacity", "gatewayQueueLimit",
                    "sensorPriority", "brakePriority" -> {
                Scenario far = farApproach(rng, s);
                yield withLoad(far, between(rng, 0.6, 1.0));
            }
            case "frictionAware", "frictionFloor", "frictionBias" -> nearBoundaryAt(rng, between(rng, 0.2, 0.55));
            case "debounceCycles", "filterWindow" -> rng.nextBoolean() ? ghost(rng) : s;
            case "fullBrakeLevel", "preBrakeLevel", "actuatorLagS" -> nearBoundaryAt(rng, between(rng, 0.8, 1.0));
            case "minActiveKph" -> nearBoundaryAtSpeed(rng, between(rng, 20, 60));
            case "maxActiveKph", "speedScale" -> nearBoundaryAtSpeed(rng, between(rng, 75, 130));
            case "rangeLimitM" -> farApproach(rng, s);
            default -> s;
        };
    }

    private static Scenario ghost(Random rng) {
        return new Scenario(Scenario.Type.GHOST, between(rng, 40, 130), between(rng, 10, 60),
                between(rng, 0.2, 1.0), between(rng, 0, 0.6), rng.nextDouble(), rng.nextLong());
    }

    private static Scenario farApproach(Random rng, Scenario s) {
        return new Scenario(Scenario.Type.OBSTACLE, s.speedKph(), between(rng, 80, 160), s.friction(),
                s.noiseM(), s.infotainmentLoad(), s.seed());
    }

    private static Scenario withLoad(Scenario s, double load) {
        return new Scenario(s.type(), s.speedKph(), s.distanceM(), s.friction(), s.noiseM(), load, s.seed());
    }

    private static Scenario nearBoundaryAt(Random rng, double friction) {
        Scenario s = nearBoundary(rng);
        double v = s.speedMs();
        double stop = v * v / (2 * friction * Simulator.G);
        return new Scenario(Scenario.Type.OBSTACLE, s.speedKph(), stop * between(rng, 1.0, 1.7) + v * between(rng, 0.2, 0.8),
                friction, s.noiseM(), s.infotainmentLoad(), s.seed()).clamped();
    }

    private static Scenario nearBoundaryAtSpeed(Random rng, double kph) {
        Scenario s = nearBoundary(rng);
        double v = kph / 3.6;
        double stop = v * v / (2 * s.friction() * Simulator.G);
        return new Scenario(Scenario.Type.OBSTACLE, kph, stop * between(rng, 1.0, 1.7) + v * between(rng, 0.2, 0.8),
                s.friction(), s.noiseM(), s.infotainmentLoad(), s.seed()).clamped();
    }

    private static double between(Random rng, double lo, double hi) {
        return lo + rng.nextDouble() * (hi - lo);
    }
}
