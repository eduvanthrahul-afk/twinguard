package com.twinguard.sim;

import java.util.List;

/** What one firmware did in one scenario. Times are NaN when the event never happened. */
public record RunResult(
        double brakeOnsetS,
        double fullBrakeS,
        double fullBrakeKph,
        double peakDecel,
        double peakBrakeRequest,
        double minGapM,
        boolean collision,
        double impactKph,
        boolean braked,
        double maxSensorAgeMs,
        int maxQueueDepth,
        int droppedMessages,
        boolean controllerCrashed,
        List<TracePoint> trace) {

    /** One sample of the replay shown in the UI. */
    public record TracePoint(
            double t,
            double gapM,
            double speedKph,
            double brakeLevel,
            double decel,
            double sensorAgeMs,
            int queueDepth) {
    }
}
