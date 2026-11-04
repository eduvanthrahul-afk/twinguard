package com.twinguard.carla;

import com.twinguard.gate.GateReport;
import com.twinguard.sim.Scenario;
import java.util.List;

/** Whether the failures found on the fast simulator also appear on the higher-fidelity one. */
public record RecheckReport(
        String backend,
        String map,
        String version,
        boolean testDouble,
        boolean calibrated,
        int checked,
        int reproduced,
        int inconclusive,
        int reproducedWithinCalibration,
        int withinCalibration,
        int controlFalseAlarms,
        List<Item> items,
        long computeMs) {

    /**
     * @param reproduced the finding appeared on CARLA, and the v1 against v1 control didn't raise it
     * @param inconclusive the finding appeared, but the control raised it too, so it isn't evidence against this release
     * @param withinCalibration whether this road friction is one where braking was matched to the fast simulator
     */
    public record Item(String findingType, Scenario scenario, boolean reproduced, boolean inconclusive, boolean withinCalibration,
                       List<String> findingsSeen, List<String> controlFindings,
                       GateReport.Metrics v1, GateReport.Metrics v2) {
    }
}
