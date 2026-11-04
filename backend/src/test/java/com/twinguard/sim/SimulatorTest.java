package com.twinguard.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SimulatorTest {

    private static Scenario obstacle(double kph, double dist, double mu, double noise, double load) {
        return new Scenario(Scenario.Type.OBSTACLE, kph, dist, mu, noise, load, 42);
    }

    @Test
    void sameScenarioGivesIdenticalResult() {
        Scenario s = obstacle(80, 70, 0.7, 0.8, 0.5);
        RunResult a = Simulator.run(Firmware.approved(), s, false);
        RunResult b = Simulator.run(Firmware.approved(), s, false);
        assertEquals(a.brakeOnsetS(), b.brakeOnsetS());
        assertEquals(a.minGapM(), b.minGapM());
        assertEquals(a.peakDecel(), b.peakDecel());
    }

    @Test
    void approvedFirmwareStopsWhenThereIsRoom() {
        RunResult r = Simulator.run(Firmware.approved(), obstacle(50, 60, 0.8, 0.2, 0.2), false);
        assertFalse(r.collision());
        assertTrue(r.braked());
    }

    @Test
    void physicsCannotBeatTheFrictionLimit() {
        // At 100 km/h on mu 0.2 the car needs about 197 m, so 40 m cannot be enough.
        RunResult r = Simulator.run(Firmware.approved(), obstacle(100, 40, 0.2, 0, 0), false);
        assertTrue(r.collision());
        assertTrue(r.peakDecel() <= 0.2 * Simulator.G + 1e-9);
    }

    @Test
    void stoppingDistanceMatchesTheoryWhenBrakingAtFullFromTheStart() {
        // Theory: v^2 / (2 * mu * g), plus a small allowance for the actuator lag and detection.
        double kph = 72;
        double mu = 0.8;
        double v = kph / 3.6;
        double theory = v * v / (2 * mu * Simulator.G);
        double start = 60;
        RunResult r = Simulator.run(Firmware.approved().with(f -> f.ttcFullBrakeS = 100),
                obstacle(kph, start, mu, 0, 0), false);
        double used = start - r.minGapM();
        double lagAllowance = v * 0.35;
        assertTrue(used >= theory, "used " + used + " theory " + theory);
        assertTrue(used <= theory + lagAllowance, "used " + used + " theory " + theory);
    }

    @Test
    void approvedFirmwareIgnoresASingleGhostReturn() {
        Scenario ghost = new Scenario(Scenario.Type.GHOST, 80, 30, 0.8, 0.1, 0, 7);
        assertFalse(Simulator.run(Firmware.approved(), ghost, false).braked());
    }

    @Test
    void healthyPrioritiesProtectBrakeTrafficFromInfotainmentLoad() {
        Scenario idle = obstacle(80, 70, 0.7, 0.2, 0.0);
        Scenario busy = obstacle(80, 70, 0.7, 0.2, 1.0);
        RunResult a = Simulator.run(Firmware.approved(), idle, false);
        RunResult b = Simulator.run(Firmware.approved(), busy, false);
        assertEquals(a.brakeOnsetS(), b.brakeOnsetS(), 0.03);
    }

    @Test
    void infotainmentPriorityBugDelaysBrakingUnderHeavyLoad() {
        Firmware buggy = Firmware.approved().with(f -> f.infotainmentPriority = 0);
        Scenario busy = obstacle(100, 130, 0.5, 0.2, 1.0);
        RunResult healthy = Simulator.run(Firmware.approved(), busy, false);
        RunResult bad = Simulator.run(buggy, busy, false);
        assertTrue(Double.isNaN(bad.brakeOnsetS()) || bad.brakeOnsetS() > healthy.brakeOnsetS() + 0.1,
                "healthy " + healthy.brakeOnsetS() + " buggy " + bad.brakeOnsetS());
    }

    @Test
    void infotainmentPriorityBugIsInvisibleUnderLightLoad() {
        Firmware buggy = Firmware.approved().with(f -> f.infotainmentPriority = 0);
        Scenario light = obstacle(100, 130, 0.5, 0.2, 0.3);
        RunResult healthy = Simulator.run(Firmware.approved(), light, false);
        RunResult bad = Simulator.run(buggy, light, false);
        assertEquals(healthy.brakeOnsetS(), bad.brakeOnsetS(), 0.03);
        assertEquals(healthy.minGapM(), bad.minGapM(), 0.5);
    }
}
