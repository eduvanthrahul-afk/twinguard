package com.twinguard.carla;

import com.fasterxml.jackson.databind.JsonNode;
import com.twinguard.sim.Firmware;
import com.twinguard.sim.Scenario;
import com.twinguard.sim.Simulator;
import com.twinguard.sim.VehiclePlant;

/**
 * The vehicle and road, simulated by CARLA. Sensors, gateway, and braking software still run in
 * Java, so the same code under test closes the loop around CARLA's physics.
 */
public final class CarlaPlant implements VehiclePlant {

    private final BridgeClient bridge;
    private double lagS;
    private double actuatorLevel;
    private double gap;
    private double speed;
    private double decel;
    private boolean collided;

    public CarlaPlant(BridgeClient bridge) {
        this.bridge = bridge;
    }

    @Override
    public void reset(Scenario s, Firmware fw) {
        lagS = fw.actuatorLagS;
        actuatorLevel = 0;
        decel = 0;
        collided = false;
        JsonNode state = bridge.reset(s.type().name(), s.speedMs(), s.distanceM(), s.friction());
        gap = state.path("gap").asDouble(s.distanceM());
        speed = state.path("speed").asDouble(s.speedMs());
    }

    @Override
    public void step(double brakeLevel) {
        // The actuator lag belongs to the firmware under test, so it stays on this side of the bridge.
        actuatorLevel += (brakeLevel - actuatorLevel) * Math.min(1.0, Simulator.DT / lagS);
        JsonNode state = bridge.step(Math.max(0, Math.min(1, actuatorLevel)));
        gap = state.path("gap").asDouble();
        speed = state.path("speed").asDouble();
        decel = state.path("decel").asDouble();
        collided = state.path("collided").asBoolean(false);
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

    @Override
    public boolean collided() {
        return collided;
    }
}
