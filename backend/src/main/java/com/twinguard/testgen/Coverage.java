package com.twinguard.testgen;

import com.twinguard.oracle.Evaluation;
import com.twinguard.sim.Scenario;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The coverage map: obstacle scenarios are binned by speed, distance, friction and infotainment
 * load, and ghost scenarios by speed and distance. Each cell keeps its most interesting scenario.
 */
public final class Coverage {

    public static final int SPEED_BINS = 8;
    public static final int DISTANCE_BINS = 8;
    public static final int FRICTION_BINS = 4;
    public static final int LOAD_BINS = 4;
    public static final int TOTAL_CELLS =
            SPEED_BINS * DISTANCE_BINS * FRICTION_BINS * LOAD_BINS + SPEED_BINS * DISTANCE_BINS;

    public record Cell(int speed, int distance, int friction, int load, boolean ghost,
                       int tests, int failures, double bestFitness) {
    }

    private static final class State {
        int tests;
        int failures;
        double bestFitness = Double.NEGATIVE_INFINITY;
        Scenario elite;
        final int speed;
        final int distance;
        final int friction;
        final int load;
        final boolean ghost;

        State(int speed, int distance, int friction, int load, boolean ghost) {
            this.speed = speed;
            this.distance = distance;
            this.friction = friction;
            this.load = load;
            this.ghost = ghost;
        }
    }

    private final Map<Integer, State> cells = new HashMap<>();
    private final List<State> elites = new ArrayList<>();

    private static int bin(double v, double lo, double hi, int bins) {
        int b = (int) ((v - lo) / (hi - lo) * bins);
        return Math.max(0, Math.min(bins - 1, b));
    }

    public static int key(Scenario s) {
        int sp = bin(s.speedKph(), Scenario.MIN_SPEED, Scenario.MAX_SPEED, SPEED_BINS);
        int di = bin(s.distanceM(), Scenario.MIN_DIST, Scenario.MAX_DIST, DISTANCE_BINS);
        if (s.type() == Scenario.Type.GHOST) {
            return 1_000_000 + sp * 100 + di;
        }
        int fr = bin(s.friction(), Scenario.MIN_FRICTION, Scenario.MAX_FRICTION, FRICTION_BINS);
        int lo = bin(s.infotainmentLoad(), 0, 1, LOAD_BINS);
        return sp * 1000 + di * 100 + fr * 10 + lo;
    }

    public boolean isCovered(Scenario s) {
        return cells.containsKey(key(s));
    }

    public void record(Evaluation e) {
        Scenario s = e.scenario();
        boolean ghost = s.type() == Scenario.Type.GHOST;
        State st = cells.computeIfAbsent(key(s), k -> {
            State created = new State(
                    bin(s.speedKph(), Scenario.MIN_SPEED, Scenario.MAX_SPEED, SPEED_BINS),
                    bin(s.distanceM(), Scenario.MIN_DIST, Scenario.MAX_DIST, DISTANCE_BINS),
                    ghost ? 0 : bin(s.friction(), Scenario.MIN_FRICTION, Scenario.MAX_FRICTION, FRICTION_BINS),
                    ghost ? 0 : bin(s.infotainmentLoad(), 0, 1, LOAD_BINS),
                    ghost);
            elites.add(created);
            return created;
        });
        st.tests++;
        if (e.detected()) {
            st.failures++;
        }
        if (e.fitness() > st.bestFitness) {
            st.bestFitness = e.fitness();
            st.elite = s;
        }
    }

    public int coveredCells() {
        return cells.size();
    }

    public double coveredPercent() {
        return 100.0 * cells.size() / TOTAL_CELLS;
    }

    /** Tournament selection: prefers cells where the two versions diverged most. */
    public Scenario pickElite(java.util.Random rng) {
        if (elites.isEmpty()) {
            return null;
        }
        State best = null;
        for (int i = 0; i < 4; i++) {
            State c = elites.get(rng.nextInt(elites.size()));
            if (best == null || c.bestFitness > best.bestFitness) {
                best = c;
            }
        }
        return best.elite;
    }

    public List<Cell> snapshot() {
        List<Cell> out = new ArrayList<>(cells.size());
        for (State s : cells.values()) {
            out.add(new Cell(s.speed, s.distance, s.friction, s.load, s.ghost, s.tests, s.failures,
                    Math.round(s.bestFitness * 100.0) / 100.0));
        }
        return out;
    }
}
