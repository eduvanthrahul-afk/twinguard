package com.twinguard.sim;

/** The software running on the braking ECU. A code mutant is a modified version of this. */
public interface BrakeController {

    void onSensor(boolean targetSeen, double measuredDistance, int createdTick);

    double decide(double speedMs, int tick);

    double sensorAgeMs(int tick);
}
