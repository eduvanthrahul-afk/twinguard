package com.twinguard.sim;

/** The physical vehicle and road. Our built-in physics and CARLA both implement this. */
public interface VehiclePlant extends AutoCloseable {

    void reset(Scenario scenario, Firmware firmware);

    /** Advances the world by one 10 ms tick with this brake request, from 0 to 1. */
    void step(double brakeLevel);

    double gapM();

    double speedMs();

    double decel();

    /** True if the world itself reports a collision. The simulation also checks for a zero gap. */
    default boolean collided() {
        return false;
    }

    @Override
    default void close() {
    }
}
