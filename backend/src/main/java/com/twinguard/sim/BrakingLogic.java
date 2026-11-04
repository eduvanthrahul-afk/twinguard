package com.twinguard.sim;

import java.util.Arrays;

/**
 * The forward collision braking logic. This class is the target of code mutation, so it must
 * stay self-contained: no lambdas or nested classes, and only one statement per line, so that
 * each mutant can be traced to a single line of source.
 */
public final class BrakingLogic implements BrakeController {

    private final Firmware fw;
    private final double friction;
    private final double[] window;
    private int windowCount;
    private int windowNext;
    private int consecutiveSeen;
    private boolean confirmed;
    private double filteredDistance = Double.POSITIVE_INFINITY;
    private int lastSampleTick = -1;
    private boolean latched;
    private boolean engaged;

    public BrakingLogic(Firmware fw, double friction) {
        this.fw = fw;
        this.friction = friction;
        this.window = new double[Math.max(1, fw.filterWindow)];
    }

    @Override
    public void onSensor(boolean targetSeen, double measuredDistance, int createdTick) {
        lastSampleTick = createdTick;
        if (!targetSeen) {
            consecutiveSeen = 0;
            confirmed = false;
            windowCount = 0;
            windowNext = 0;
            filteredDistance = Double.POSITIVE_INFINITY;
            return;
        }
        consecutiveSeen = consecutiveSeen + 1;
        if (consecutiveSeen >= fw.debounceCycles) {
            confirmed = true;
        }
        window[windowNext] = measuredDistance + fw.distanceBiasM;
        windowNext = (windowNext + 1) % window.length;
        windowCount = Math.min(windowCount + 1, window.length);
        filteredDistance = median();
    }

    private double median() {
        double[] copy = Arrays.copyOf(window, windowCount);
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    @Override
    public double sensorAgeMs(int tick) {
        if (lastSampleTick < 0) {
            return 0;
        }
        return (tick - lastSampleTick) * Simulator.DT * 1000;
    }

    @Override
    public double decide(double speedMs, int tick) {
        double ageMs = sensorAgeMs(tick);
        double wheelSpeed = speedMs * fw.speedScale;
        double kph = wheelSpeed * 3.6;

        if (latched) {
            if (speedMs <= 0.05) {
                latched = false;
                return 0;
            }
            return fw.fullBrakeLevel;
        }
        // Once an intervention has begun, it is held to a standstill rather than released at low speed.
        if (engaged && kph < fw.minActiveKph) {
            if (fw.latchFullBrake) {
                latched = true;
            }
            return fw.fullBrakeLevel;
        }
        if (!confirmed) {
            return 0;
        }
        if (ageMs > fw.staleTimeoutMs) {
            return 0;
        }
        if (kph < fw.minActiveKph) {
            return 0;
        }
        if (kph > fw.maxActiveKph) {
            return 0;
        }
        if (wheelSpeed <= 0) {
            return 0;
        }

        double d = filteredDistance;
        double ttc = d / wheelSpeed;
        double mu = 0.8;
        if (fw.frictionAware) {
            mu = Math.max(fw.frictionFloor, friction + fw.frictionBias);
        }
        mu = Math.max(0.05, mu);
        double stopDistance = wheelSpeed * wheelSpeed / (2 * mu * Simulator.G) * fw.stopMargin + fw.stopBufferM;

        boolean ttcFull = ttc < fw.ttcFullBrakeS;
        if (fw.flipTtcFullCompare) {
            ttcFull = ttc > fw.ttcFullBrakeS;
        }
        boolean stopFull = d < stopDistance;
        if (fw.flipStopDistanceCompare) {
            stopFull = d > stopDistance;
        }

        if (ttcFull || stopFull) {
            engaged = true;
            if (fw.latchFullBrake) {
                latched = true;
            }
            return fw.fullBrakeLevel;
        }
        if (ttc < fw.ttcPreBrakeS) {
            engaged = true;
            return fw.preBrakeLevel;
        }
        return 0;
    }
}
