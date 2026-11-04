package com.twinguard.oracle;

import com.twinguard.sim.RunResult;
import com.twinguard.sim.Scenario;
import com.twinguard.sim.Simulator;
import java.util.ArrayList;
import java.util.List;

/** Decides whether a candidate's behaviour is a problem, using v1 and fixed safety rules. */
public final class Oracle {

    static final double LATE_TOLERANCE_S = 0.15;
    static final double EARLY_TOLERANCE_S = 0.40;
    static final double WEAK_RATIO = 0.90;
    static final double IMPACT_TOLERANCE_KPH = 2.0;
    static final double MARGIN_TOLERANCE_M = 1.5;
    static final double LOW_MARGIN_M = 5.0;
    static final double HARSH_BRAKE_MIN_KPH = 10.0;
    /** A manifest can never excuse stopping closer than this. */
    static final double MIN_GAP_FLOOR_M = 1.0;

    private Oracle() {
    }

    /**
     * True when a reasonable system has clearly enough room to stop: the physical stopping
     * distance with a 25% margin, plus 0.6 s of reaction travel and 3 m.
     */
    public static boolean collisionIsAvoidable(Scenario s) {
        double v = s.speedMs();
        double physical = v * v / (2 * s.friction() * Simulator.G);
        return s.distanceM() >= physical * 1.25 + v * 0.6 + 3.0;
    }

    public static List<Finding> judge(Scenario s, RunResult v1, RunResult v2, ChangeManifest manifest) {
        List<Finding> out = new ArrayList<>();

        if (v2.controllerCrashed() && !v1.controllerCrashed()) {
            out.add(Finding.of(Finding.Type.SOFTWARE_CRASH, "The braking software crashed, and stopped requesting brakes"));
        }
        if (s.type() == Scenario.Type.GHOST) {
            if (v2.braked() && !v1.braked()) {
                out.add(Finding.of(Finding.Type.PHANTOM_BRAKE,
                        String.format("Braked at %.2f s for a spurious radar return, with no real obstacle",
                                v2.brakeOnsetS())));
            }
            return out;
        }

        if (v2.collision() && collisionIsAvoidable(s)) {
            out.add(Finding.of(Finding.Type.SAFETY_VIOLATION,
                    String.format("Hit the obstacle at %.0f km/h although there was room to stop%s",
                            v2.impactKph(), v1.collision() ? " (v1 fails this scenario too)" : "")));
        }
        if (v2.collision() && !v1.collision()) {
            out.add(Finding.of(Finding.Type.NEW_COLLISION,
                    String.format("Hit the obstacle at %.0f km/h, while v1 stopped %.1f m short",
                            v2.impactKph(), v1.minGapM())));
        }
        if (v2.collision() && v1.collision() && v2.impactKph() > v1.impactKph() + IMPACT_TOLERANCE_KPH) {
            out.add(Finding.of(Finding.Type.WORSE_IMPACT,
                    String.format("Impact speed rose from %.0f to %.0f km/h", v1.impactKph(), v2.impactKph())));
        }
        if (v1.braked() && !v2.braked()) {
            out.add(Finding.of(Finding.Type.MISSED_BRAKE,
                    String.format("Never braked, while v1 braked at %.2f s", v1.brakeOnsetS())));
        }
        if (v1.braked() && v2.braked()) {
            double shift = v2.brakeOnsetS() - v1.brakeOnsetS();
            if (shift > LATE_TOLERANCE_S + manifest.allowedLateS()) {
                out.add(Finding.of(Finding.Type.LATE_BRAKE,
                        String.format("Braking began %.0f ms later than v1", shift * 1000)));
            }
            if (-shift > EARLY_TOLERANCE_S + manifest.allowedEarlyS()) {
                out.add(Finding.of(Finding.Type.EARLY_BRAKE,
                        String.format("Braking began %.0f ms earlier than v1, a nuisance-braking risk", -shift * 1000)));
            }
            // Holding the brake to a standstill at crawl speed is not harsh braking.
            if (!Double.isNaN(v1.fullBrakeS()) && !Double.isNaN(v2.fullBrakeS())
                    && v2.fullBrakeKph() >= HARSH_BRAKE_MIN_KPH
                    && v1.fullBrakeS() - v2.fullBrakeS() > EARLY_TOLERANCE_S + manifest.allowedEarlyS()) {
                out.add(Finding.of(Finding.Type.EARLY_BRAKE,
                        String.format("Full braking began %.0f ms earlier than v1, a harsh-braking risk",
                                (v1.fullBrakeS() - v2.fullBrakeS()) * 1000)));
            }
            // A gentler request only counts as weak braking if it costs real stopping distance.
            double allowedGapLoss = MARGIN_TOLERANCE_M + manifest.allowedGapLossM();
            if (v2.peakBrakeRequest() < v1.peakBrakeRequest() * WEAK_RATIO
                    && v2.minGapM() < v1.minGapM() - allowedGapLoss) {
                out.add(Finding.of(Finding.Type.WEAK_BRAKE,
                        String.format("Peak brake request fell from %.0f%% to %.0f%%",
                                v1.peakBrakeRequest() * 100, v2.peakBrakeRequest() * 100)));
            }
        }
        if (!v1.collision() && !v2.collision()) {
            boolean belowFloor = v2.minGapM() < MIN_GAP_FLOOR_M && v2.minGapM() < v1.minGapM();
            boolean undeclaredLoss = v2.minGapM() < LOW_MARGIN_M
                    && v2.minGapM() < v1.minGapM() - MARGIN_TOLERANCE_M - manifest.allowedGapLossM();
            if (belowFloor || undeclaredLoss) {
                out.add(Finding.of(Finding.Type.REDUCED_MARGIN,
                        String.format("Stopping gap shrank from %.1f m to %.1f m", v1.minGapM(), v2.minGapM())));
            }
        }
        return out;
    }
}
