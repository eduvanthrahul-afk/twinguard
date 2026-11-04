package com.twinguard.catalog;

import com.twinguard.sim.Firmware;
import java.util.function.Consumer;

/** A deliberately injected bug, applied on top of the approved firmware. */
public record Fault(String id, FaultClass faultClass, String title, String description, boolean holdout,
                    Consumer<Firmware> change) {

    public enum FaultClass {
        THRESHOLD("Braking thresholds"),
        LOGIC("Decision logic"),
        SENSOR("Sensor processing"),
        TIMING("Timing and latency"),
        ACTUATOR("Brake actuator"),
        FRICTION("Road-friction handling"),
        NETWORK("Infotainment and network");

        public final String label;

        FaultClass(String label) {
            this.label = label;
        }
    }

    public Firmware firmware() {
        return Firmware.approved().with(change);
    }
}
