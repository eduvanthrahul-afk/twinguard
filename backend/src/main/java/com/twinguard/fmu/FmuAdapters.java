package com.twinguard.fmu;

import com.twinguard.feature.FeatureController;
import com.twinguard.feature.VehicleFeature;
import com.twinguard.sim.BrakeController;
import com.twinguard.sim.Firmware;
import com.twinguard.sim.Simulator;
import java.util.List;
import java.util.function.Supplier;

/** Lets an FMU stand in as the software under test, so TwinGuard can test code it didn't write. */
public final class FmuAdapters {

    /** The signals a forward collision braking FMU must offer. */
    public static final List<String> BRAKE_INPUTS = List.of("sensor_new", "target_seen", "measured_distance", "sensor_tick", "tick", "speed", "friction");
    public static final List<String> BRAKE_OUTPUTS = List.of("brake_level");

    /** A radar message can arrive between control steps, so it's delivered in a step of almost no time. */
    private static final double SENSOR_STEP_SECONDS = 1e-9;

    private FmuAdapters() {
    }

    public static Supplier<FeatureController> forFeature(FmuFile fmu, VehicleFeature feature) {
        fmu.requireContract(feature.inputNames(), feature.outputNames());
        double seconds = feature.stepSeconds();
        return () -> new FeatureFmu(fmu.newInstance(feature.inputNames(), feature.outputNames()), seconds);
    }

    /** Firmware that runs the FMU's braking code, on the same simulated network and vehicle as everything else. */
    public static Firmware brakeFirmware(FmuFile fmu) {
        fmu.requireContract(BRAKE_INPUTS, BRAKE_OUTPUTS);
        return Firmware.approved().withController((firmware, friction) -> new BrakeFmu(fmu.newInstance(BRAKE_INPUTS, BRAKE_OUTPUTS), friction));
    }

    private static final class FeatureFmu implements FeatureController, AutoCloseable {
        private final FmuFile.Instance instance;
        private final double seconds;

        FeatureFmu(FmuFile.Instance instance, double seconds) {
            this.instance = instance;
            this.seconds = seconds;
        }

        @Override
        public double[] step(double[] inputs) {
            return instance.step(inputs, seconds).clone();
        }

        @Override
        public void close() {
            instance.close();
        }
    }

    private static final class BrakeFmu implements BrakeController, AutoCloseable {
        private final FmuFile.Instance instance;
        private final double friction;
        private final double[] inputs = new double[7];
        private int lastSampleTick = -1;

        BrakeFmu(FmuFile.Instance instance, double friction) {
            this.instance = instance;
            this.friction = friction;
            inputs[6] = friction;
        }

        @Override
        public void onSensor(boolean targetSeen, double measuredDistance, int createdTick) {
            lastSampleTick = createdTick;
            inputs[0] = 1;
            inputs[1] = targetSeen ? 1 : 0;
            inputs[2] = measuredDistance;
            inputs[3] = createdTick;
            instance.step(inputs, SENSOR_STEP_SECONDS);
        }

        @Override
        public double decide(double speedMs, int tick) {
            inputs[0] = 0;
            inputs[4] = tick;
            inputs[5] = speedMs;
            return instance.step(inputs, Simulator.DT)[0];
        }

        @Override
        public double sensorAgeMs(int tick) {
            return lastSampleTick < 0 ? 0 : (tick - lastSampleTick) * Simulator.DT * 1000;
        }

        @Override
        public void close() {
            instance.close();
        }
    }
}
