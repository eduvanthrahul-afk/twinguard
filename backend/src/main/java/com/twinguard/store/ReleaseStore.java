package com.twinguard.store;

import com.twinguard.catalog.ReleasePackage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Release packages imported by users. They survive restarts and sit beside the built-in demo releases. */
@Repository
public class ReleaseStore {

    public record Stored(ReleasePackage pkg, Instant importedAt) {
    }

    private final JdbcTemplate jdbc;

    public ReleaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds the package, or replaces an imported package with the same id. */
    public void save(ReleasePackage pkg) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbc.update("UPDATE release_package SET feature = ?, title = ?, package = ?, imported_at = ? WHERE id = ?",
                pkg.feature(), pkg.title(), pkg.toJson(), now, pkg.id());
        if (updated == 0) {
            jdbc.update("INSERT INTO release_package (id, feature, title, package, imported_at) VALUES (?, ?, ?, ?, ?)",
                    pkg.id(), pkg.feature(), pkg.title(), pkg.toJson(), now);
        }
    }

    public List<Stored> all() {
        return jdbc.query("SELECT package, imported_at FROM release_package ORDER BY imported_at",
                (rs, i) -> new Stored(ReleasePackage.parse(rs.getString(1)), rs.getTimestamp(2).toInstant()));
    }

    public List<ReleasePackage> forFeature(String feature) {
        return all().stream().map(Stored::pkg).filter(p -> p.feature().equals(feature)).toList();
    }

    public Optional<ReleasePackage> find(String feature, String id) {
        return forFeature(feature).stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public Optional<ReleasePackage> find(String id) {
        return all().stream().map(Stored::pkg).filter(p -> p.id().equals(id)).findFirst();
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM release_package WHERE id = ?", id) > 0;
    }
}
