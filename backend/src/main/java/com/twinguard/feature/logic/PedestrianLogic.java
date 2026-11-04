package com.twinguard.feature.logic;

import com.twinguard.feature.FeatureController;
import java.util.Map;

/**
 * The pedestrian emergency stop software. It reads simulated detections, not camera images. This
 * class is the target of code mutation, so it must stay self-contained, with one statement per line.
 *
 * Inputs: detected, distance, lateral, lateral_speed, speed, friction. Output: brake_command.
 */
public final class PedestrianLogic implements FeatureController {

    private static final double G = 9.81;

    private final double confirmSamples;
    private final double pathHalfWidth;
    private final double stopMargin;
    private final double stopBuffer;
    private final double predictionMargin;
    private final double brakeLevel;
    private final double dropoutSamples;
    private double seenCount;
    private double missedCount;
    private boolean latched;

    public PedestrianLogic(Map<String, Double> s) {
        confirmSamples = s.get("confirmSamples");
        pathHalfWidth = s.get("pathHalfWidth");
        stopMargin = s.get("stopMargin");
        stopBuffer = s.get("stopBuffer");
        predictionMargin = s.get("predictionMargin");
        brakeLevel = s.get("brakeLevel");
        dropoutSamples = s.get("dropoutSamples");
    }

    @Override
    public double[] step(double[] in) {
        double detected = in[0];
        double distance = in[1];
        double lateral = in[2];
        double lateralSpeed = in[3];
        double speed = in[4];
        double friction = in[5];

        if (latched) {
            return new double[] {brakeLevel};
        }
        if (detected > 0.5) {
            seenCount = seenCount + 1;
            missedCount = 0;
        } else {
            missedCount = missedCount + 1;
            if (missedCount > dropoutSamples) {
                seenCount = 0;
            }
            return new double[] {0};
        }
        if (seenCount < confirmSamples) {
            return new double[] {0};
        }
        if (speed <= 0) {
            return new double[] {0};
        }

        double side = Math.abs(lateral);
        boolean inPath = side < pathHalfWidth;
        double closingOnPath = lateral > 0 ? -lateralSpeed : lateralSpeed;
        double timeToCar = distance / speed;
        boolean willEnter = false;
        if (closingOnPath > 0) {
            double timeToPath = (side - pathHalfWidth) / closingOnPath;
            willEnter = timeToPath < timeToCar + predictionMargin;
        }
        if (!inPath && !willEnter) {
            return new double[] {0};
        }

        double stopDistance = speed * speed / (2 * friction * G) * stopMargin + stopBuffer;
        if (distance < stopDistance) {
            latched = true;
            return new double[] {brakeLevel};
        }
        return new double[] {0};
    }
}
