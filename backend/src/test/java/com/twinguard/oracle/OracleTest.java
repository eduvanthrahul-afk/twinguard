package com.twinguard.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.twinguard.catalog.FaultCatalog;
import com.twinguard.sim.Firmware;
import com.twinguard.sim.Scenario;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OracleTest {

    private static Scenario randomScenario(Random r) {
        return new Scenario(
                r.nextDouble() < 0.15 ? Scenario.Type.GHOST : Scenario.Type.OBSTACLE,
                20 + r.nextDouble() * 110,
                10 + r.nextDouble() * 150,
                0.2 + r.nextDouble() * 0.8,
                r.nextDouble() * 1.5,
                r.nextDouble(),
                r.nextLong());
    }

    @Test
    void identicalFirmwareNeverRaisesAFinding() {
        Evaluator e = new Evaluator(Firmware.approved(), Firmware.approved(), ChangeManifest.NONE);
        Random r = new Random(1);
        int findings = 0;
        for (int i = 0; i < 3000; i++) {
            Evaluation result = e.evaluate(randomScenario(r));
            if (result.detected() && result.findings().stream()
                    .anyMatch(f -> f.type() != Finding.Type.SAFETY_VIOLATION)) {
                findings++;
            }
        }
        assertEquals(0, findings);
    }

    @Test
    void approvedFirmwareMeetsTheFixedSafetyRule() {
        Evaluator e = new Evaluator(Firmware.approved(), Firmware.approved(), ChangeManifest.NONE);
        Random r = new Random(2);
        int violations = 0;
        for (int i = 0; i < 5000; i++) {
            if (e.evaluate(randomScenario(r)).detected()) {
                violations++;
            }
        }
        assertEquals(0, violations, "v1 must be clean, or the safety rule is wrong");
    }

    @Test
    void faultCatalogIsGeneratedWithAHoldoutSet() {
        assertTrue(FaultCatalog.all().size() >= 50, "size " + FaultCatalog.all().size());
        long holdout = FaultCatalog.all().stream().filter(f -> f.holdout()).count();
        assertTrue(holdout >= 12, "holdout " + holdout);
        long uniqueIds = FaultCatalog.all().stream().map(f -> f.id()).distinct().count();
        assertEquals(FaultCatalog.all().size(), uniqueIds);
    }
}
