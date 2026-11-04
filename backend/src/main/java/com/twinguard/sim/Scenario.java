package com.twinguard.sim;

/**
 * One simulated road situation. Both firmware versions receive an identical Scenario,
 * including the noise seed, so any difference in behaviour comes from the firmware.
 */
public record Scenario(
        Type type,
        double speedKph,
        double distanceM,
        double friction,
        double noiseM,
        double infotainmentLoad,
        long seed) {

    public enum Type {
        /** A stationary obstacle ahead: the car must stop. */
        OBSTACLE,
        /** No real obstacle, only a 40 ms spurious radar return: the car must not brake. */
        GHOST
    }

    public static final double MIN_SPEED = 20, MAX_SPEED = 130;
    public static final double MIN_DIST = 10, MAX_DIST = 160;
    public static final double MIN_FRICTION = 0.2, MAX_FRICTION = 1.0;
    public static final double MAX_NOISE = 1.5;

    public double speedMs() {
        return speedKph / 3.6;
    }

    public Scenario withSeed(long newSeed) {
        return new Scenario(type, speedKph, distanceM, friction, noiseM, infotainmentLoad, newSeed);
    }

    /** Keeps every parameter inside the range the simulator is meant to cover. */
    public Scenario clamped() {
        return new Scenario(type,
                clamp(speedKph, MIN_SPEED, MAX_SPEED),
                clamp(distanceM, MIN_DIST, MAX_DIST),
                clamp(friction, MIN_FRICTION, MAX_FRICTION),
                clamp(noiseM, 0, MAX_NOISE),
                clamp(infotainmentLoad, 0, 1),
                seed);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
