package com.twinguard.feature.logic;

import com.twinguard.feature.FeatureController;
import java.util.Map;

/**
 * The adaptive cruise control software. This class is the target of code mutation, so it must stay
 * self-contained, with one statement per line.
 *
 * Inputs: gap, ego_speed, lead_speed, set_speed. Output: accel_command.
 */
public final class CruiseLogic implements FeatureController {

    private static final double STEP_SECONDS = 0.05;
    private static final double MIN_LEAD_BRAKING = 0.5;

    private final double headway;
    private final double standstillGap;
    private final double gapGain;
    private final double speedGain;
    private final double maxAccel;
    private final double brakeMargin;
    private final double filter;
    private final double anticipation;
    private double filteredGap = -1;
    private double previousLead = -1;

    public CruiseLogic(Map<String, Double> s) {
        headway = s.get("headway");
        standstillGap = s.get("standstillGap");
        gapGain = s.get("gapGain");
        speedGain = s.get("speedGain");
        maxAccel = s.get("maxAccel");
        brakeMargin = s.get("brakeMargin");
        filter = s.get("filter");
        anticipation = s.get("anticipation");
    }

    @Override
    public double[] step(double[] in) {
        double gap = in[0];
        double ego = in[1];
        double lead = in[2];
        double set = in[3];

        if (filteredGap < 0) {
            filteredGap = gap;
        } else {
            filteredGap = filteredGap + filter * (gap - filteredGap);
        }

        double accel = speedGain * (set - ego);
        double desiredGap = standstillGap + headway * ego;
        double closing = ego - lead;
        double follow = gapGain * (filteredGap - desiredGap) - speedGain * closing;
        if (follow < accel) {
            accel = follow;
        }
        if (closing > 0) {
            double room = Math.max(0.5, filteredGap - standstillGap / 2);
            double needed = closing * closing / (2 * room) * brakeMargin;
            if (-needed < accel) {
                accel = -needed;
            }
        }

        // If the lead car is braking, plan to stop behind where it will stop.
        double leadBraking = 0;
        if (previousLead >= 0) {
            leadBraking = (previousLead - lead) / STEP_SECONDS;
        }
        previousLead = lead;
        if (leadBraking > MIN_LEAD_BRAKING) {
            double leadStop = lead * lead / (2 * leadBraking);
            double stopRoom = Math.max(0.5, filteredGap + leadStop - standstillGap / 2);
            double stopNeeded = ego * ego / (2 * stopRoom) * brakeMargin * anticipation;
            if (-stopNeeded < accel) {
                accel = -stopNeeded;
            }
        }
        if (accel > maxAccel) {
            accel = maxAccel;
        }
        return new double[] {accel};
    }
}
