package com.twinguard.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Every gate run and its evidence, so a release decision can be looked up after the server restarts. */
@Repository
public class RunStore {

    public static final String GATE = "gate";
    public static final String FEATURE = "feature";

    public enum Status { RUNNING, DONE, FAILED }

    /** One run, without its report. The request is the JSON the dashboard sent to start it. */
    public record Entry(String id, String scope, String feature, String targetId, String title, String summary, String request,
                        Status status, String verdict, String error, Integer evaluations, Integer failing,
                        Instant startedAt, Instant finishedAt) {
    }

    private static final String COLUMNS = "id, scope, feature, target_id, title, summary, request, status, verdict, error, evaluations, failing, started_at, finished_at";
    private static final int MAX_LIST = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RunStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** A run that was going when the server stopped can never finish, so it is marked as failed. */
    @PostConstruct
    void closeInterruptedRuns() {
        jdbc.update("UPDATE gate_run SET status = ?, error = ?, finished_at = ? WHERE status = ?",
                Status.FAILED.name(), "The server stopped before this run finished. Run it again.", Timestamp.from(Instant.now()), Status.RUNNING.name());
    }

    public void started(String id, String scope, String feature, String targetId, String title, String summary, Object request) {
        jdbc.update("INSERT INTO gate_run (id, scope, feature, target_id, title, summary, request, status, started_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, scope, feature, truncate(targetId, 256), truncate(title, 512), truncate(summary, 1024), write(request),
                Status.RUNNING.name(), Timestamp.from(Instant.now()));
    }

    public void finished(String id, String verdict, int evaluations, int failing, Object report) {
        jdbc.update("UPDATE gate_run SET status = ?, verdict = ?, evaluations = ?, failing = ?, report = ?, finished_at = ? WHERE id = ?",
                Status.DONE.name(), verdict, evaluations, failing, write(report), Timestamp.from(Instant.now()), id);
    }

    public void failed(String id, String error) {
        jdbc.update("UPDATE gate_run SET status = ?, error = ?, finished_at = ? WHERE id = ?",
                Status.FAILED.name(), truncate(error, 1024), Timestamp.from(Instant.now()), id);
    }

    public Optional<Entry> find(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM gate_run WHERE id = ?", RunStore::entry, id).stream().findFirst();
    }

    public <T> Optional<T> report(String id, Class<T> type) {
        List<String> found = jdbc.query("SELECT report FROM gate_run WHERE id = ? AND report IS NOT NULL", (rs, i) -> rs.getString(1), id);
        return found.stream().findFirst().map(s -> read(s, type));
    }

    /** The newest runs first. Scope and feature are optional filters. */
    public List<Entry> list(String scope, String feature, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM gate_run WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (scope != null) {
            sql.append(" AND scope = ?");
            args.add(scope);
        }
        if (feature != null) {
            sql.append(" AND feature = ?");
            args.add(feature);
        }
        sql.append(" ORDER BY started_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, MAX_LIST)));
        return jdbc.query(sql.toString(), RunStore::entry, args.toArray());
    }

    /** The newest finished run of each target, which is what the release tables show. */
    public List<Entry> latestPerTarget() {
        return jdbc.query("SELECT " + COLUMNS + " FROM gate_run r WHERE status <> 'RUNNING' AND started_at = ("
                + "SELECT MAX(started_at) FROM gate_run x WHERE x.scope = r.scope AND COALESCE(x.feature, '') = COALESCE(r.feature, '') "
                + "AND x.target_id = r.target_id AND x.status <> 'RUNNING') ORDER BY started_at DESC", RunStore::entry);
    }

    public <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A saved record couldn't be read: " + e.getOriginalMessage(), e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Couldn't save a record: " + e.getOriginalMessage(), e);
        }
    }

    private static Entry entry(ResultSet rs, int row) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new Entry(rs.getString("id"), rs.getString("scope"), rs.getString("feature"), rs.getString("target_id"), rs.getString("title"),
                rs.getString("summary"), rs.getString("request"), Status.valueOf(rs.getString("status")), rs.getString("verdict"), rs.getString("error"),
                (Integer) rs.getObject("evaluations"), (Integer) rs.getObject("failing"),
                rs.getTimestamp("started_at").toInstant(), finished == null ? null : finished.toInstant());
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
