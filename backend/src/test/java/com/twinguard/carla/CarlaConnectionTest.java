package com.twinguard.carla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.twinguard.gate.GateReport;
import com.twinguard.gate.GateTargets;
import com.twinguard.gate.ReleaseGate;
import com.twinguard.testgen.ScenarioSearch;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;

/** Tests the Java side of the connection against a fake bridge. This does not prove anything about real CARLA. */
class CarlaConnectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A stand-in bridge with simple friction-limited physics, built to follow the bridge protocol. */
    private static final class FakeBridge implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final Thread thread = new Thread(this::serve, "fake-bridge");
        double gap;
        double speed;
        double friction;
        double distance;
        boolean ghost;

        FakeBridge() throws IOException {
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            try (Socket socket = server.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                String line;
                while ((line = in.readLine()) != null) {
                    out.println(JSON.writeValueAsString(handle(JSON.readTree(line))));
                }
            } catch (IOException e) {
                // The test closed the socket.
            }
        }

        private Object handle(JsonNode request) {
            switch (request.path("cmd").asText()) {
                case "ping":
                    return java.util.Map.of("ok", true, "backend", "test-double", "map", "FakeTown", "version", "0");
                case "reset":
                    ghost = request.path("type").asText().equals("GHOST");
                    speed = request.path("speed").asDouble();
                    distance = request.path("distance").asDouble();
                    friction = request.path("friction").asDouble();
                    gap = distance;
                    return java.util.Map.of("ok", true, "gap", gap, "speed", speed);
                case "step":
                    double decel = Math.min(request.path("brake").asDouble() * 11, friction * 9.81);
                    speed = Math.max(0, speed - decel * 0.01);
                    if (!ghost) {
                        gap -= speed * 0.01;
                    }
                    return java.util.Map.of("ok", true, "gap", gap, "speed", speed, "decel", decel, "collided", false);
                default:
                    return java.util.Map.of("ok", false, "error", "unknown command");
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    @Test
    void recheckRunsTheRealFailureThroughTheBridge() throws IOException {
        ReleaseGate.Target target = GateTargets.release("v2.2-media");
        GateReport report = ReleaseGate.run(target, 800, ScenarioSearch.Strategy.CHANGE_AWARE, 5, null);
        assertFalse(report.counterexamples().isEmpty());
        // One connection serves every simulation, because each reset reuses it.
        try (FakeBridge fake = new FakeBridge(); BridgeClient client = new BridgeClient("127.0.0.1", fake.server.getLocalPort())) {
            RecheckReport result = HighFidelityRecheck.run(client, target, report);
            assertTrue(result.testDouble(), "the report must say this was not real CARLA");
            assertEquals(report.counterexamples().size(), result.checked());
            assertTrue(result.reproduced() > 0, "the fake bridge copies our physics, so failures should reproduce");
        }
    }

    @Test
    void unreachableBridgeGivesAClearError() throws IOException {
        int unusedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            unusedPort = probe.getLocalPort();
        }
        BridgeException e = assertThrows(BridgeException.class, () -> new BridgeClient("127.0.0.1", unusedPort));
        assertTrue(e.getMessage().contains("bridge.py"), e.getMessage());
    }
}
