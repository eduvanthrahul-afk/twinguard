package com.twinguard.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Measured scores, kept so the server doesn't spend a minute re-measuring them on every start.
 * Each result is tied to a fingerprint of the code that produced it. When the code changes, the
 * fingerprint changes and the scores are measured again.
 */
@Repository
public class CampaignCache {

    public record Cached<T>(T result, Instant measuredAt) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(CampaignCache.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String fingerprint;

    public CampaignCache(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.fingerprint = codeFingerprint();
    }

    public <T> Optional<Cached<T>> load(String name, Class<T> type) {
        if (fingerprint == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT result, measured_at FROM campaign_result WHERE name = ? AND fingerprint = ?",
                (rs, i) -> new Cached<>(rs.getString(1), rs.getTimestamp(2).toInstant()), name, fingerprint).stream().findFirst().flatMap(c -> {
                    try {
                        return Optional.of(new Cached<>(json.readValue(c.result(), type), c.measuredAt()));
                    } catch (JsonProcessingException e) {
                        LOG.warn("Saved scores for {} couldn't be read, so they will be measured again: {}", name, e.getOriginalMessage());
                        return Optional.empty();
                    }
                });
    }

    public void save(String name, Object result) {
        if (fingerprint == null) {
            return;
        }
        try {
            String text = json.writeValueAsString(result);
            Timestamp now = Timestamp.from(Instant.now());
            if (jdbc.update("UPDATE campaign_result SET fingerprint = ?, result = ?, measured_at = ? WHERE name = ?", fingerprint, text, now, name) == 0) {
                jdbc.update("INSERT INTO campaign_result (name, fingerprint, result, measured_at) VALUES (?, ?, ?, ?)", name, fingerprint, text, now);
            }
        } catch (JsonProcessingException e) {
            LOG.warn("Scores for {} couldn't be saved: {}", name, e.getOriginalMessage());
        }
    }

    /**
     * A hash of TwinGuard's own code: the jar when it runs from one, or the compiled classes and
     * resources in development. Null when neither can be found, which turns the cache off.
     */
    static String codeFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            boolean found = false;
            for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                Path path = Path.of(entry);
                if (Files.isRegularFile(path) && entry.endsWith("twinguard.jar")) {
                    found |= hash(digest, path);
                } else if (Files.isDirectory(path) && Files.isDirectory(path.resolve("com/twinguard"))) {
                    try (Stream<Path> files = Files.walk(path)) {
                        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                            digest.update(path.relativize(file).toString().getBytes());
                            found |= hash(digest, file);
                        }
                    }
                }
            }
            return found ? HexFormat.of().formatHex(digest.digest()) : null;
        } catch (NoSuchAlgorithmException | IOException e) {
            LOG.warn("Couldn't fingerprint the code, so scores will be measured on every start: {}", e.getMessage());
            return null;
        }
    }

    private static boolean hash(MessageDigest digest, Path file) throws IOException {
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return true;
    }

    public boolean enabled() {
        return fingerprint != null;
    }
}
