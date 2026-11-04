package com.twinguard.api;

import com.twinguard.carla.BridgeClient;
import com.twinguard.carla.BridgeException;
import com.twinguard.carla.HighFidelityRecheck;
import com.twinguard.carla.RecheckReport;
import com.twinguard.gate.GateReport;
import com.twinguard.gate.ReleaseGate;
import org.springframework.stereotype.Service;

/** Connects the dashboard to the CARLA bridge. CARLA runs one simulation at a time, so re-checks run one at a time. */
@Service
public class CarlaService {

    public record Status(boolean connected, String backend, String map, String version, boolean testDouble,
                         boolean calibrated, String message) {
    }

    private static final int STATUS_TIMEOUT_MS = 2_000;

    private volatile boolean busy;

    public Status status() {
        if (busy) {
            return new Status(true, null, null, null, false, false, "A re-check is running");
        }
        // The bridge serves one client at a time, so a status check must not wait long behind another client.
        try (BridgeClient bridge = new BridgeClient(BridgeClient.DEFAULT_HOST, BridgeClient.DEFAULT_PORT, STATUS_TIMEOUT_MS)) {
            BridgeClient.Info info = bridge.ping();
            return new Status(true, info.backend(), info.map(), info.version(), "test-double".equals(info.backend()), info.calibrated(), null);
        } catch (BridgeException e) {
            return new Status(false, null, null, null, false, false, e.getMessage());
        }
    }

    public synchronized RecheckReport recheck(ReleaseGate.Target target, GateReport report) {
        busy = true;
        try (BridgeClient bridge = new BridgeClient(BridgeClient.DEFAULT_HOST, BridgeClient.DEFAULT_PORT)) {
            return HighFidelityRecheck.run(bridge, target, report);
        } finally {
            busy = false;
        }
    }
}
