package com.twinguard.feature.logic;

import com.twinguard.feature.FeatureController;
import java.util.Map;

/**
 * The battery management software, during charging. This class is the target of code mutation, so it
 * must stay self-contained, with one statement per line.
 *
 * Inputs: cell_voltage, cell_temperature, requested_current. Outputs: current_limit, contactor_closed.
 */
public final class BatteryLogic implements FeatureController {

    private final double taperVoltage;
    private final double cutoffVoltage;
    private final double taperGain;
    private final double derateTemperature;
    private final double cutoffTemperature;
    private final double coldTemperature;
    private final double coldCurrentLimit;
    private final double fullCurrent;
    private final double maxCurrent;
    private final double confirmSamples;
    private final double ramp;
    private double limit;
    private double output;
    private double overVoltageCount;
    private boolean tapering;
    private boolean faulted;
    private boolean finished;

    public BatteryLogic(Map<String, Double> s) {
        taperVoltage = s.get("taperVoltage");
        cutoffVoltage = s.get("cutoffVoltage");
        taperGain = s.get("taperGain");
        derateTemperature = s.get("derateTemperature");
        cutoffTemperature = s.get("cutoffTemperature");
        coldTemperature = s.get("coldTemperature");
        coldCurrentLimit = s.get("coldCurrentLimit");
        fullCurrent = s.get("fullCurrent");
        maxCurrent = s.get("maxCurrent");
        confirmSamples = s.get("confirmSamples");
        ramp = s.get("ramp");
    }

    @Override
    public double[] step(double[] in) {
        double voltage = in[0];
        double temperature = in[1];
        double requested = in[2];

        if (voltage >= cutoffVoltage) {
            overVoltageCount = overVoltageCount + 1;
        } else {
            overVoltageCount = 0;
        }
        if (overVoltageCount >= confirmSamples) {
            faulted = true;
        }
        if (temperature >= cutoffTemperature) {
            faulted = true;
        }

        if (voltage > taperVoltage) {
            tapering = true;
            limit = limit - taperGain * (voltage - taperVoltage);
        } else {
            limit = Math.min(limit + ramp, Math.min(maxCurrent, requested));
        }
        if (limit < 0) {
            limit = 0;
        }

        double allowed = limit;
        if (temperature > derateTemperature) {
            double share = (cutoffTemperature - temperature) / (cutoffTemperature - derateTemperature);
            allowed = allowed * Math.max(0, share);
        }
        if (temperature < coldTemperature) {
            allowed = Math.min(allowed, coldCurrentLimit);
        }

        if (tapering && limit < fullCurrent) {
            finished = true;
        }
        if (faulted || finished) {
            return new double[] {0, 0};
        }
        // The final output is ramped too, because a limit can lift suddenly when a cold cell warms up.
        if (allowed > output + ramp) {
            allowed = output + ramp;
        }
        output = allowed;
        return new double[] {output, 1};
    }
}
