package com.twinguard.api;

import com.twinguard.feature.FeatureReport;
import com.twinguard.gate.GateReport;
import com.twinguard.store.RunStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Every release decision TwinGuard has made, newest first. Reports are fetched from the run endpoints. */
@RestController
@RequestMapping("/api/history")
public class HistoryApi {

    /** The newest result for one target, with its report, so the dashboard can show it after a restart. */
    public record Latest(RunStore.Entry run, Object report) {
    }

    private static final int MAX_LATEST = 100;

    private final RunStore store;

    public HistoryApi(RunStore store) {
        this.store = store;
    }

    @GetMapping
    public List<RunStore.Entry> list(@RequestParam(required = false) String scope, @RequestParam(required = false) String feature,
                                     @RequestParam(defaultValue = "100") int limit) {
        if (scope != null && !RunStore.GATE.equals(scope) && !RunStore.FEATURE.equals(scope)) {
            throw new IllegalArgumentException("scope must be gate or feature");
        }
        return store.list(scope, feature, limit);
    }

    @GetMapping("/latest")
    public List<Latest> latest() {
        return store.latestPerTarget().stream().limit(MAX_LATEST).map(e -> new Latest(e, report(e).orElse(null))).toList();
    }

    private Optional<?> report(RunStore.Entry e) {
        if (e.status() != RunStore.Status.DONE) {
            return Optional.empty();
        }
        return RunStore.GATE.equals(e.scope()) ? store.report(e.id(), GateReport.class) : store.report(e.id(), FeatureReport.class);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }
}
