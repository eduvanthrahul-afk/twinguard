package com.twinguard.oracle;

import com.twinguard.sim.RunResult;
import com.twinguard.sim.Scenario;
import java.util.List;

/** The outcome of testing a candidate in one scenario. */
public record Evaluation(
        Scenario scenario,
        RunResult v1,
        RunResult v2,
        List<Finding> findings,
        double divergence,
        double criticality,
        int simulations) {

    public double fitness() {
        return divergence + 0.3 * criticality;
    }

    public boolean detected() {
        return !findings.isEmpty();
    }
}
