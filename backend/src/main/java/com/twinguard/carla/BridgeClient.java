package com.twinguard.carla;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Talks to carla_bridge/bridge.py using one line of JSON per message over a local TCP socket. */
public final class BridgeClient implements AutoCloseable {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 9797;

    private static final int CONNECT_TIMEOUT_MS = 2_000;
    /** Loading a map or spawning vehicles in CARLA can take a while. */
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Info(String backend, String map, String version, boolean calibrated) {
    }

    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;

    public BridgeClient(String host, int port) {
        this(host, port, READ_TIMEOUT_MS);
    }

    public BridgeClient(String host, int port, int readTimeoutMs) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(readTimeoutMs);
            socket.setTcpNoDelay(true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BridgeException("Can't reach the CARLA bridge at " + host + ":" + port + ". Is bridge.py running?", e);
        }
    }

    public Info ping() {
        JsonNode reply = call(JSON.createObjectNode().put("cmd", "ping"));
        return new Info(reply.path("backend").asText("unknown"), reply.path("map").asText("unknown"), reply.path("version").asText("unknown"),
                reply.path("calibrated").asBoolean(false));
    }

    public JsonNode reset(String type, double speedMs, double distanceM, double friction) {
        return call(JSON.createObjectNode().put("cmd", "reset").put("type", type).put("speed", speedMs)
                .put("distance", distanceM).put("friction", friction));
    }

    public JsonNode step(double brake) {
        return call(JSON.createObjectNode().put("cmd", "step").put("brake", brake));
    }

    private JsonNode call(ObjectNode request) {
        try {
            out.write(JSON.writeValueAsString(request));
            out.write('\n');
            out.flush();
            String line = in.readLine();
            if (line == null) {
                throw new BridgeException("The CARLA bridge closed the connection", null);
            }
            JsonNode reply = JSON.readTree(line);
            if (!reply.path("ok").asBoolean(false)) {
                throw new BridgeException("The CARLA bridge reported: " + reply.path("error").asText("an unknown error"), null);
            }
            return reply;
        } catch (IOException e) {
            throw new BridgeException("Lost the connection to the CARLA bridge: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // Nothing useful can be done if closing the socket fails.
        }
    }
}
