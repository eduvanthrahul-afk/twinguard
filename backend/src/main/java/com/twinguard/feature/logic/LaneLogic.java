package com.twinguard.feature.logic;

import com.twinguard.feature.FeatureController;
import java.util.Map;

/**
 * The lane keeping software. This class is the target of code mutation, so it must stay
 * self-contained, with one statement per line.
 *
 * Inputs: lane_offset, heading_error, speed, curvature. Output: steer_command.
 */
public final class LaneLogic implements FeatureController {

    private static final double WHEELBASE = 2.8;

    private final double offsetGain;
    private final double headingGain;
    private final double feedForward;
    private final double deadband;
    private final double maxSteer;
    private final double filter;
    private final double minSpeed;
    private double filteredOffset;
    private boolean started;

    public LaneLogic(Map<String, Double> s) {
        offsetGain = s.get("offsetGain");
        headingGain = s.get("headingGain");
        feedForward = s.get("feedForward");
        deadband = s.get("deadband");
        maxSteer = s.get("maxSteer");
        filter = s.get("filter");
        minSpeed = s.get("minSpeed");
    }

    @Override
    public double[] step(double[] in) {
        double offset = in[0];
        double heading = in[1];
        double speed = in[2];
        double curvature = in[3];

        if (!started) {
            filteredOffset = offset;
            started = true;
        } else {
            filteredOffset = filteredOffset + filter * (offset - filteredOffset);
        }
        if (speed < minSpeed) {
            return new double[] {0};
        }

        double error = filteredOffset;
        if (Math.abs(error) < deadband) {
            error = 0;
        }
        double correction = -offsetGain * error / Math.max(speed, 5) - headingGain * heading;
        double steer = feedForward * WHEELBASE * curvature + correction;
        if (steer > maxSteer) {
            steer = maxSteer;
        }
        if (steer < -maxSteer) {
            steer = -maxSteer;
        }
        return new double[] {steer};
    }
}
