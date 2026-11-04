package com.twinguard.gate;

import com.twinguard.catalog.CustomFault;
import com.twinguard.catalog.Fault;
import com.twinguard.catalog.FaultCatalog;
import com.twinguard.catalog.Release;
import com.twinguard.catalog.ReleaseCatalog;
import com.twinguard.mutation.CodeMutant;
import com.twinguard.mutation.CodeMutator;
import com.twinguard.oracle.ChangeManifest;
import java.util.Map;

/** Turns a release, catalog fault or Fault Lab entry into something the gate can test. */
public final class GateTargets {

    private GateTargets() {
    }

    public static ReleaseGate.Target release(String id) {
        Release r = ReleaseCatalog.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown release: " + id));
        return new ReleaseGate.Target(r.id(), r.title(), "release", r.firmware(), r.manifest());
    }

    public static ReleaseGate.Target fault(String id) {
        Fault f = FaultCatalog.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown fault: " + id));
        return new ReleaseGate.Target(f.id(), "Injected fault: " + f.title(), "fault", f.firmware(), ChangeManifest.NONE);
    }

    public static ReleaseGate.Target mutant(String id) {
        CodeMutant m = CodeMutator.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown code mutant: " + id));
        return new ReleaseGate.Target(m.id(), "Code mutant " + m.id(), "mutant", m.firmware(), ChangeManifest.NONE);
    }

    public static ReleaseGate.Target custom(Map<String, Double> settings) {
        return new ReleaseGate.Target("custom", "Fault Lab: custom fault", "custom",
                CustomFault.build(settings), ChangeManifest.NONE);
    }
}
