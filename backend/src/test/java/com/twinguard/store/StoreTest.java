package com.twinguard.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.twinguard.catalog.ReleasePackage;
import com.twinguard.feature.FeatureGate;
import com.twinguard.feature.FeatureReport;
import com.twinguard.feature.Features;
import com.twinguard.feature.VehicleFeature;
import com.twinguard.gate.GateReport;
import com.twinguard.gate.GateTargets;
import com.twinguard.gate.ReleaseGate;
import com.twinguard.testgen.ScenarioSearch;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class StoreTest {

    private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void freshDatabase() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(ds);
    }

    @Test
    void gateReportSurvivesTheDatabase() {
        RunStore store = new RunStore(jdbc, json);
        GateReport report = ReleaseGate.run(GateTargets.release("v2.2-media"), 300, ScenarioSearch.Strategy.CHANGE_AWARE, 3, null);
        store.started("r1", RunStore.GATE, null, report.targetId(), report.targetTitle(), "s", Map.of("kind", "release", "id", "v2.2-media"));
        store.finished("r1", report.verdict().name(), report.evaluations(), report.failingScenarios(), report);

        GateReport back = store.report("r1", GateReport.class).orElseThrow();
        assertEquals(json.valueToTree(report), json.valueToTree(back));
        RunStore.Entry entry = store.find("r1").orElseThrow();
        assertEquals(RunStore.Status.DONE, entry.status());
        assertEquals(report.verdict().name(), entry.verdict());
    }

    @Test
    void featureReportSurvivesTheDatabase() {
        RunStore store = new RunStore(jdbc, json);
        VehicleFeature battery = Features.find("battery").orElseThrow();
        VehicleFeature.Release r = battery.releases().get(0);
        FeatureReport report = FeatureGate.run(battery, r.id(), r.title(), List.of(), battery.source(Map.of()), battery.source(r.overrides()),
                r.declaredChanges(), 200, 3, null);
        store.started("f1", RunStore.FEATURE, "battery", r.id(), r.title(), "s", Map.of());
        store.finished("f1", report.verdict().name(), report.evaluations(), report.failingScenarios(), report);
        assertEquals(json.valueToTree(report), json.valueToTree(store.report("f1", FeatureReport.class).orElseThrow()));
    }

    @Test
    void runsLeftRunningAreClosedOnStartAndLatestIsPerTarget() {
        RunStore store = new RunStore(jdbc, json);
        store.started("a", RunStore.GATE, null, "t", "T", "", Map.of());
        store.failed("a", "boom");
        store.started("b", RunStore.GATE, null, "t", "T", "", Map.of());
        store.finished("b", "APPROVE", 1, 0, Map.of());
        store.started("c", RunStore.GATE, null, "u", "U", "", Map.of());

        new RunStore(jdbc, json).closeInterruptedRuns();
        assertEquals(RunStore.Status.FAILED, store.find("c").orElseThrow().status());
        List<RunStore.Entry> latest = store.latestPerTarget();
        assertEquals(2, latest.size());
        assertTrue(latest.stream().anyMatch(e -> e.id().equals("b")));
        assertEquals(3, store.list(null, null, 10).size());
    }

    @Test
    void importedPackagesAreReplacedById() {
        ReleaseStore store = new ReleaseStore(jdbc);
        store.save(ReleasePackage.parse("{\"id\": \"p\", \"title\": \"One\", \"feature\": \"braking\", \"changes\": {\"stopMargin\": 1}}"));
        store.save(ReleasePackage.parse("{\"id\": \"p\", \"title\": \"Two\", \"feature\": \"braking\", \"changes\": {\"stopMargin\": 1}}"));
        assertEquals(1, store.all().size());
        assertEquals("Two", store.find("braking", "p").orElseThrow().title());
        assertTrue(store.delete("p"));
        assertTrue(store.all().isEmpty());
    }

    @Test
    void campaignResultsAreReused() {
        CampaignCache cache = new CampaignCache(jdbc, json);
        if (!cache.enabled()) {
            return;
        }
        cache.save("x", Map.of("score", 42));
        assertEquals(42, cache.load("x", Map.class).orElseThrow().result().get("score"));
    }
}
