package com.twinguard.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Deterministic closed-loop simulation of a mocked vehicle:
 * sensor ECU -> gateway -> braking ECU -> gateway -> brake actuator -> vehicle plant,
 * with infotainment traffic competing for the same gateway. The plant is pluggable.
 */
public final class ClosedLoopSimulator implements VehicleSimulator {

    private static final double DT = Simulator.DT;
    private static final int SENSOR_PERIOD_TICKS = 2;
    private static final double BACKGROUND_MSGS_PER_TICK = 1.5;
    private static final double GHOST_START_S = 1.0;
    private static final double GHOST_END_S = 1.04;
    private static final double GHOST_DURATION_S = 4.0;
    private static final double OBSTACLE_DURATION_S = 15.0;
    private static final int TRACE_EVERY_TICKS = 2;

    private enum Kind { SENSOR, BRAKE, BACKGROUND, INFOTAINMENT }

    private record Message(Kind kind, double value, boolean targetSeen, int createdTick, int releaseTick) {
    }

    private static final Message BACKGROUND = new Message(Kind.BACKGROUND, 0, false, 0, 0);
    private static final Message INFOTAINMENT = new Message(Kind.INFOTAINMENT, 0, false, 0, 0);

    private final Supplier<VehiclePlant> plants;

    public ClosedLoopSimulator(Supplier<VehiclePlant> plants) {
        this.plants = plants;
    }

    @Override
    public RunResult run(Firmware fw, Scenario scenario, boolean withTrace) {
        try (VehiclePlant plant = plants.get()) {
            plant.reset(scenario, fw);
            Run run = new Run(fw, scenario, withTrace, plant);
            try {
                return run.execute();
            } finally {
                run.close();
            }
        }
    }

    private static final class Run {
        private final Firmware fw;
        private final Scenario s;
        private final boolean withTrace;
        private final VehiclePlant plant;
        private final BrakeController controller;
        private final Random noise;

        // Gateway: three priority classes, with a bounded queue for each.
        @SuppressWarnings("unchecked")
        private final ArrayDeque<Message>[] queues = new ArrayDeque[] {
                new ArrayDeque<Message>(), new ArrayDeque<Message>(), new ArrayDeque<Message>()
        };
        private final ArrayDeque<Message> delayLine = new ArrayDeque<>();
        private double backgroundCredit;
        private double infotainmentCredit;
        private int dropped;
        private int maxQueue;
        private boolean controllerCrashed;
        private double commandedLevel;
        private double maxSensorAgeMs;

        Run(Firmware fw, Scenario s, boolean withTrace, VehiclePlant plant) {
            this.fw = fw;
            this.s = s;
            this.withTrace = withTrace;
            this.plant = plant;
            this.controller = fw.newController(s.friction());
            this.noise = new Random(s.seed());
        }

        /** An FMU holds native memory, which has to be released after each simulation. */
        void close() {
            if (controller instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    // The run has already finished, and a failure to release its memory can't change the result.
                }
            }
        }

        RunResult execute() {
            boolean ghost = s.type() == Scenario.Type.GHOST;
            double duration = ghost ? GHOST_DURATION_S : OBSTACLE_DURATION_S;
            int ticks = (int) Math.round(duration / DT);

            double onset = Double.NaN;
            double full = Double.NaN;
            double fullKph = Double.NaN;
            double peakDecel = 0;
            double peakRequest = 0;
            double minGap = plant.gapM();
            boolean collision = false;
            double impactKph = 0;
            List<RunResult.TracePoint> trace = withTrace ? new ArrayList<>() : List.of();

            for (int tick = 0; tick < ticks; tick++) {
                double t = tick * DT;

                generateTraffic(tick, t, ghost);
                serveGateway();
                runBrakeEcu(tick);

                if (commandedLevel > 0 && Double.isNaN(onset)) {
                    onset = t;
                }
                if (commandedLevel >= 0.75 && Double.isNaN(full)) {
                    full = t;
                    fullKph = plant.speedMs() * 3.6;
                }

                plant.step(commandedLevel);

                double decel = plant.decel();
                double speed = plant.speedMs();
                double gap = plant.gapM();
                peakDecel = Math.max(peakDecel, decel);
                peakRequest = Math.max(peakRequest, commandedLevel);
                if (!ghost) {
                    minGap = Math.min(minGap, gap);
                }

                if (withTrace && tick % TRACE_EVERY_TICKS == 0) {
                    trace.add(point(t, gap, speed * 3.6, decel, tick));
                }

                if (!ghost && (gap <= 0 || plant.collided())) {
                    collision = true;
                    impactKph = speed * 3.6;
                    minGap = 0;
                    if (withTrace) {
                        trace.add(point(t + DT, 0, impactKph, decel, tick));
                    }
                    break;
                }
                if (!ghost && speed <= 0) {
                    if (withTrace) {
                        trace.add(point(t + DT, gap, 0, decel, tick));
                    }
                    break;
                }
            }

            return new RunResult(onset, full, fullKph, peakDecel, peakRequest, minGap, collision, impactKph,
                    !Double.isNaN(onset), maxSensorAgeMs, maxQueue, dropped, controllerCrashed, trace);
        }

        private RunResult.TracePoint point(double t, double gap, double kph, double decel, int tick) {
            return new RunResult.TracePoint(round(t), round(gap), round(kph), round(commandedLevel), round(decel),
                    round(ageMs(tick)), queueDepth());
        }

        private void generateTraffic(int tick, double t, boolean ghost) {
            // Noise is drawn on every sensor period, whatever the firmware does, so that
            // v1 and v2 always see exactly the same noise at the same moment.
            if (tick % SENSOR_PERIOD_TICKS == 0) {
                double n = noise.nextGaussian() * s.noiseM();
                double gap = plant.gapM();
                boolean visible = ghost
                        ? t >= GHOST_START_S && t < GHOST_END_S
                        : gap + n <= fw.rangeLimitM;
                double measured = ghost ? s.distanceM() + n : gap + n;
                delayLine.addLast(new Message(Kind.SENSOR, measured, visible, tick, tick + fw.sensorDelayMs / 10));
            }
            while (!delayLine.isEmpty() && delayLine.peekFirst().releaseTick() <= tick) {
                offer(delayLine.pollFirst(), fw.sensorPriority);
            }

            backgroundCredit += BACKGROUND_MSGS_PER_TICK;
            while (backgroundCredit >= 1) {
                backgroundCredit -= 1;
                offer(BACKGROUND, 1);
            }

            infotainmentCredit += s.infotainmentLoad() * fw.infotainmentRate * fw.gatewayCapacity;
            while (infotainmentCredit >= 1) {
                infotainmentCredit -= 1;
                offer(INFOTAINMENT, fw.infotainmentPriority);
            }
        }

        private void offer(Message m, int priority) {
            ArrayDeque<Message> q = queues[priority];
            if (q.size() >= fw.gatewayQueueLimit) {
                dropped++;
                return;
            }
            q.addLast(m);
            maxQueue = Math.max(maxQueue, queueDepth());
        }

        private int queueDepth() {
            return queues[0].size() + queues[1].size() + queues[2].size();
        }

        private void serveGateway() {
            int budget = fw.gatewayCapacity;
            for (ArrayDeque<Message> q : queues) {
                while (budget > 0 && !q.isEmpty()) {
                    Message m = q.pollFirst();
                    budget--;
                    if (m.kind() == Kind.SENSOR) {
                        deliverSensor(m);
                    } else if (m.kind() == Kind.BRAKE) {
                        commandedLevel = m.value();
                    }
                }
            }
        }

        /** A software exception takes the ECU down, as it would in a car: it stops requesting brakes. */
        private void deliverSensor(Message m) {
            if (controllerCrashed) {
                return;
            }
            try {
                controller.onSensor(m.targetSeen(), m.value(), m.createdTick());
            } catch (RuntimeException e) {
                controllerCrashed = true;
            }
        }

        private double ageMs(int tick) {
            if (controllerCrashed) {
                return 0;
            }
            return controller.sensorAgeMs(tick);
        }

        private void runBrakeEcu(int tick) {
            double level = 0;
            if (!controllerCrashed) {
                try {
                    maxSensorAgeMs = Math.max(maxSensorAgeMs, controller.sensorAgeMs(tick));
                    level = controller.decide(plant.speedMs(), tick);
                } catch (RuntimeException e) {
                    controllerCrashed = true;
                }
            }
            // The ECU sends a brake command on every tick, and it must cross the gateway.
            offer(new Message(Kind.BRAKE, level, false, tick, tick), fw.brakePriority);
        }

        private static double round(double v) {
            return Math.round(v * 1000.0) / 1000.0;
        }
    }
}
