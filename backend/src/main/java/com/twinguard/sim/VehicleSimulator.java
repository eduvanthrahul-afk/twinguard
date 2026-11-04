package com.twinguard.sim;

/**
 * The one question TwinGuard asks a simulator: given this firmware and scenario, what happened?
 * The test search, safety checks, and release decision depend only on this interface.
 */
public interface VehicleSimulator {

    RunResult run(Firmware firmware, Scenario scenario, boolean withTrace);
}
