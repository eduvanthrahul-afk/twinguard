package com.twinguard.sim;

/** Shared constants, and the default simulator: the closed loop on fast built-in physics. */
public final class Simulator {

    public static final double DT = 0.01;
    public static final double G = 9.81;
    public static final VehicleSimulator FAST = new ClosedLoopSimulator(PhysicsPlant::new);

    private Simulator() {
    }

    public static RunResult run(Firmware fw, Scenario scenario, boolean withTrace) {
        return FAST.run(fw, scenario, withTrace);
    }
}
