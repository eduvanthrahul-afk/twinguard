package com.twinguard.sim;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Every setting of the software under test. A release or an injected fault is expressed as a
 * copy of the approved v1 firmware with some of these values changed.
 */
public final class Firmware implements Cloneable {

    // Braking decision thresholds
    public double ttcPreBrakeS = 2.4;
    public double ttcFullBrakeS = 1.2;
    public double stopMargin = 1.2;
    public double stopBufferM = 2.0;
    public double preBrakeLevel = 0.4;
    public double fullBrakeLevel = 1.0;
    public double minActiveKph = 5;
    public double maxActiveKph = 200;

    // Decision logic
    public boolean flipTtcFullCompare = false;
    public boolean flipStopDistanceCompare = false;
    public boolean latchFullBrake = true;
    public boolean frictionAware = true;
    public double frictionFloor = 0.0;
    public double frictionBias = 0.0;

    // Sensor processing
    public int debounceCycles = 3;
    public int filterWindow = 5;
    public int sensorDelayMs = 0;
    public double distanceBiasM = 0;
    public double speedScale = 1.0;
    public double rangeLimitM = 160;
    public int staleTimeoutMs = 250;

    // Brake actuator
    public double actuatorLagS = 0.12;

    // In-vehicle network: lower priority number is served first
    public int sensorPriority = 0;
    public int brakePriority = 0;
    public int infotainmentPriority = 2;
    public double infotainmentRate = 1.0;
    public int gatewayCapacity = 8;
    public int gatewayQueueLimit = 256;

    /** Which braking code this firmware runs. Null means the original code. */
    private ControllerFactory controllerFactory;

    public interface ControllerFactory {
        BrakeController create(Firmware firmware, double friction);
    }

    public static Firmware approved() {
        return new Firmware();
    }

    public Firmware withController(ControllerFactory factory) {
        Firmware copy = copy();
        copy.controllerFactory = factory;
        return copy;
    }

    BrakeController newController(double friction) {
        return controllerFactory == null ? new BrakingLogic(this, friction) : controllerFactory.create(this, friction);
    }

    public Firmware with(Consumer<Firmware> change) {
        Firmware copy = copy();
        change.accept(copy);
        return copy;
    }

    public Firmware copy() {
        try {
            return (Firmware) clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The settings that differ from another firmware. This drives change-aware test prioritisation. */
    public List<String> changedFrom(Firmware other) {
        List<String> changed = new ArrayList<>();
        for (Field f : Firmware.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                if (!f.get(this).equals(f.get(other))) {
                    changed.add(f.getName());
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return changed;
    }
}
