package com.twinguard.catalog;

import com.twinguard.catalog.Fault.FaultClass;
import com.twinguard.sim.Firmware;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Generates the fault set from mutation operators applied across magnitudes, instead of a few
 * hand-picked bugs. Small magnitudes are included on purpose: some should be hard to catch.
 * About one fault in four is held out and never used to improve the test corpus.
 */
public final class FaultCatalog {

    private static final int HOLDOUT_EVERY = 4;
    private static final List<Fault> ALL = build();

    private FaultCatalog() {
    }

    public static List<Fault> all() {
        return ALL;
    }

    public static Optional<Fault> find(String id) {
        return ALL.stream().filter(f -> f.id().equals(id)).findFirst();
    }

    private static List<Fault> build() {
        Builder b = new Builder();

        for (double k : new double[] {0.5, 0.7, 0.85, 0.95, 1.5}) {
            b.add(FaultClass.THRESHOLD, "ttc-full-x" + k, "Full-brake TTC threshold scaled ×" + k,
                    "Time-to-collision threshold for full braking is changed by a factor of " + k,
                    f -> f.ttcFullBrakeS *= k);
        }
        for (double k : new double[] {0.5, 0.75, 0.95, 1.5}) {
            b.add(FaultClass.THRESHOLD, "ttc-pre-x" + k, "Pre-brake TTC threshold scaled ×" + k,
                    "Time-to-collision threshold for pre-braking is changed by a factor of " + k,
                    f -> f.ttcPreBrakeS *= k);
        }
        for (double k : new double[] {0.6, 0.8, 0.9, 0.97, 1.5}) {
            b.add(FaultClass.THRESHOLD, "stop-margin-x" + k, "Stopping-distance margin scaled ×" + k,
                    "Safety margin on the computed stopping distance is changed by a factor of " + k,
                    f -> f.stopMargin *= k);
        }

        b.add(FaultClass.LOGIC, "flip-ttc", "Reversed full-brake TTC comparison",
                "The TTC comparison uses > instead of <", f -> f.flipTtcFullCompare = true);
        b.add(FaultClass.LOGIC, "flip-stop", "Reversed stopping-distance comparison",
                "The distance comparison uses > instead of <", f -> f.flipStopDistanceCompare = true);
        b.add(FaultClass.LOGIC, "no-latch", "Full brake no longer held",
                "Full braking releases as soon as the trigger condition clears", f -> f.latchFullBrake = false);
        for (double kph : new double[] {15, 30, 50}) {
            b.add(FaultClass.LOGIC, "min-active-" + (int) kph, "Inactive below " + (int) kph + " km/h",
                    "The feature's low-speed gate is raised", f -> f.minActiveKph = kph);
        }
        for (double kph : new double[] {80, 100, 120}) {
            b.add(FaultClass.LOGIC, "max-active-" + (int) kph, "Inactive above " + (int) kph + " km/h",
                    "The feature's high-speed gate is lowered", f -> f.maxActiveKph = kph);
        }

        for (int n : new int[] {1, 2, 6, 12}) {
            b.add(FaultClass.SENSOR, "debounce-" + n, "Target confirmation set to " + n + " samples",
                    "The number of consecutive radar returns needed to confirm a target changes",
                    f -> f.debounceCycles = n);
        }
        for (int n : new int[] {1, 3, 15, 31}) {
            b.add(FaultClass.SENSOR, "filter-" + n, "Distance filter window set to " + n,
                    "The median filter window on radar distance changes", f -> f.filterWindow = n);
        }
        for (double m : new double[] {0.5, 2, 5, 10}) {
            b.add(FaultClass.SENSOR, "bias-" + m, "Distance over-read by " + m + " m",
                    "A calibration error makes obstacles look further away", f -> f.distanceBiasM = m);
        }
        for (double k : new double[] {0.62, 0.9, 0.97}) {
            b.add(FaultClass.SENSOR, "speed-x" + k, "Wheel speed scaled ×" + k,
                    "A unit or calibration error (0.62 is km/h read as mph)", f -> f.speedScale = k);
        }
        for (double m : new double[] {30, 60, 100, 140}) {
            b.add(FaultClass.SENSOR, "range-" + (int) m, "Radar range limited to " + (int) m + " m",
                    "Targets beyond this distance are ignored", f -> f.rangeLimitM = m);
        }

        for (int ms : new int[] {20, 50, 100, 200, 400}) {
            b.add(FaultClass.TIMING, "delay-" + ms, "Sensor data delayed by " + ms + " ms",
                    "Radar samples reach the braking ECU late", f -> f.sensorDelayMs = ms);
        }
        for (int ms : new int[] {30, 60, 2000}) {
            b.add(FaultClass.TIMING, "stale-" + ms, "Stale-data timeout set to " + ms + " ms",
                    "The age at which sensor data is rejected changes", f -> f.staleTimeoutMs = ms);
        }

        for (double k : new double[] {0.5, 0.7, 0.9}) {
            b.add(FaultClass.ACTUATOR, "brake-force-x" + k, "Full-brake force scaled ×" + k,
                    "The maximum brake request is reduced", f -> f.fullBrakeLevel = k);
        }
        b.add(FaultClass.ACTUATOR, "pre-force-0.1", "Pre-brake force reduced to 10%",
                "The pre-brake request is almost removed", f -> f.preBrakeLevel = 0.1);
        for (double s : new double[] {0.2, 0.4, 0.8}) {
            b.add(FaultClass.ACTUATOR, "lag-" + s, "Actuator response slowed to " + s + " s",
                    "Brake pressure builds more slowly", f -> f.actuatorLagS = s);
        }

        b.add(FaultClass.FRICTION, "friction-ignored", "Road friction ignored",
                "The controller assumes a dry road everywhere", f -> f.frictionAware = false);
        for (double v : new double[] {0.3, 0.4, 0.6}) {
            b.add(FaultClass.FRICTION, "friction-floor-" + v, "Friction estimate floored at " + v,
                    "Slippery roads are treated as grippier than they are", f -> f.frictionFloor = v);
        }
        for (double v : new double[] {0.05, 0.15, 0.3}) {
            b.add(FaultClass.FRICTION, "friction-bias-" + v, "Friction over-estimated by " + v,
                    "The road-surface estimate reads too high", f -> f.frictionBias = v);
        }

        b.add(FaultClass.NETWORK, "infotainment-top-priority", "Infotainment given top priority",
                "Media traffic is served ahead of radar and brake messages", f -> f.infotainmentPriority = 0);
        b.add(FaultClass.NETWORK, "infotainment-flood", "Infotainment flood at top priority",
                "A retry loop raises media traffic by 50% at top priority", f -> {
                    f.infotainmentPriority = 0;
                    f.infotainmentRate = 1.5;
                });
        b.add(FaultClass.NETWORK, "sensor-low-priority", "Radar messages demoted",
                "Radar traffic drops to the lowest gateway priority", f -> f.sensorPriority = 2);
        b.add(FaultClass.NETWORK, "brake-low-priority", "Brake messages demoted",
                "Brake traffic drops to the lowest gateway priority", f -> f.brakePriority = 2);
        for (int cap : new int[] {2, 4, 6}) {
            b.add(FaultClass.NETWORK, "gateway-capacity-" + cap, "Gateway throughput cut to " + cap + " per tick",
                    "Gateway throughput is reduced from 8 messages per tick", f -> f.gatewayCapacity = cap);
        }
        b.add(FaultClass.NETWORK, "infotainment-rate-1.2", "Infotainment traffic up 20%",
                "More media traffic, but at the correct low priority", f -> f.infotainmentRate = 1.2);

        return List.copyOf(b.faults);
    }

    private static final class Builder {
        final List<Fault> faults = new ArrayList<>();

        void add(FaultClass faultClass, String id, String title, String description, Consumer<Firmware> change) {
            boolean holdout = faults.size() % HOLDOUT_EVERY == HOLDOUT_EVERY - 1;
            faults.add(new Fault(id, faultClass, title, description, holdout, change));
        }
    }
}
