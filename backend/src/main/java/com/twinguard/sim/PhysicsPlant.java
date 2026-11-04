package com.twinguard.sim;

/** The fast built-in vehicle model: friction-limited braking with a first-order actuator lag. */
public final class PhysicsPlant implements VehiclePlant {

    private static final double MAX_SYSTEM_DECEL = 11.0;

    private double friction;
    private double lagS;
    private boolean ghost;
    private double decel;
    private double speed;
    private double gap;

    @Override
    public void reset(Scenario s, Firmware fw) {
        friction = s.friction();
        lagS = fw.actuatorLagS;
        ghost = s.type() == Scenario.Type.GHOST;
        decel = 0;
        speed = s.speedMs();
        gap = s.distanceM();
    }

    @Override
    public void step(double brakeLevel) {
        double target = Math.min(brakeLevel * MAX_SYSTEM_DECEL, friction * Simulator.G);
        decel += (target - decel) * Math.min(1.0, Simulator.DT / lagS);
        if (decel < 0.001) {
            decel = 0;
        }
        speed = Math.max(0, speed - decel * Simulator.DT);
        if (!ghost) {
            gap -= speed * Simulator.DT;
        }
    }

    @Override
    public double gapM() {
        return gap;
    }

    @Override
    public double speedMs() {
        return speed;
    }

    @Override
    public double decel() {
        return decel;
    }
}
