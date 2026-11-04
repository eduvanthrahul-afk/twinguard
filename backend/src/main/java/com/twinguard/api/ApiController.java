package com.twinguard.api;

import com.twinguard.carla.BridgeException;
import com.twinguard.carla.RecheckReport;
import com.twinguard.catalog.CustomFault;
import com.twinguard.catalog.Fault;
import com.twinguard.catalog.FaultCatalog;
import com.twinguard.catalog.Release;
import com.twinguard.catalog.ReleaseCatalog;
import com.twinguard.fmu.FmuAdapters;
import com.twinguard.fmu.FmuException;
import com.twinguard.fmu.FmuRegistry;
import com.twinguard.oracle.ChangeManifest;
import com.twinguard.gate.GateReport;
import com.twinguard.gate.GateTargets;
import com.twinguard.gate.ReleaseGate;
import com.twinguard.mutation.CodeMutator;
import com.twinguard.sim.Firmware;
import com.twinguard.testgen.ScenarioSearch;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final int DEFAULT_BUDGET = 1500;
    private static final int MIN_BUDGET = 100;
    private static final int MAX_BUDGET = 5000;

    public record ReleaseView(String id, String title, String summary, String component, List<String> changedSettings, String intent) {
    }

    public record FaultView(String id, String faultClass, String classLabel, String title, String description, boolean holdout) {
    }

    public record SettingView(String key, String label, String unit, double min, double max, double step, double approved) {
    }

    public record MutantView(String id, String method, int line, String operator, String description, String sourceLine) {
    }

    public record Catalog(List<ReleaseView> releases, List<FaultView> faults, List<SettingView> settings, List<MutantView> mutants) {
    }

    public record RunRequest(String kind, String id, Map<String, Double> settings, Integer budget, String strategy, String baseline, String candidate) {
    }

    private final RunService runs;
    private final CampaignService campaign;

    private final CarlaService carla;
    private final FmuRegistry fmus;

    public ApiController(RunService runs, CampaignService campaign, CarlaService carla, FmuRegistry fmus) {
        this.runs = runs;
        this.campaign = campaign;
        this.carla = carla;
        this.fmus = fmus;
    }

    @GetMapping("/catalog")
    public Catalog catalog() {
        Firmware approved = Firmware.approved();
        List<ReleaseView> releases = ReleaseCatalog.all().stream().map((Release r) -> new ReleaseView(r.id(), r.title(), r.summary(),
                r.component(), r.firmware().changedFrom(approved), r.manifest().intent())).toList();
        List<FaultView> faults = FaultCatalog.all().stream().map((Fault f) -> new FaultView(f.id(), f.faultClass().name(),
                f.faultClass().label, f.title(), f.description(), f.holdout())).toList();
        List<SettingView> settings = CustomFault.SETTINGS.stream().map(s -> new SettingView(s.key(), s.label(), s.unit(),
                s.min(), s.max(), s.step(), s.approved())).toList();
        List<MutantView> mutants = CodeMutator.all().stream().map(m -> new MutantView(m.id(), m.method(), m.line(), m.operator(),
                m.description(), m.sourceLine())).toList();
        return new Catalog(releases, faults, settings, mutants);
    }

    @PostMapping("/runs")
    public Map<String, String> start(@RequestBody RunRequest request) {
        if (request.kind() == null) {
            throw new IllegalArgumentException("A run needs a kind: release, fault or custom");
        }
        ReleaseGate.Target target = switch (request.kind()) {
            case "release" -> GateTargets.release(request.id());
            case "fault" -> GateTargets.fault(request.id());
            case "mutant" -> GateTargets.mutant(request.id());
            case "custom" -> GateTargets.custom(request.settings() == null ? Map.of() : request.settings());
            case "fmu" -> fmuTarget(request);
            default -> throw new IllegalArgumentException("Unknown run kind: " + request.kind());
        };
        int budget = request.budget() == null ? DEFAULT_BUDGET : request.budget();
        if (budget < MIN_BUDGET || budget > MAX_BUDGET) {
            throw new IllegalArgumentException("Budget must be between " + MIN_BUDGET + " and " + MAX_BUDGET);
        }
        ScenarioSearch.Strategy strategy = request.strategy() == null
                ? ScenarioSearch.Strategy.CHANGE_AWARE
                : ScenarioSearch.Strategy.valueOf(request.strategy());
        return Map.of("runId", runs.start(target, budget, strategy));
    }

    /** Tests one supplier's braking FMU against another, on the same simulated vehicle and network. */
    private ReleaseGate.Target fmuTarget(RunRequest request) {
        if (request.baseline() == null || request.candidate() == null) {
            throw new IllegalArgumentException("An FMU run needs a baseline and a candidate file");
        }
        return new ReleaseGate.Target(request.candidate(), request.candidate() + " vs " + request.baseline(), "fmu",
                FmuAdapters.brakeFirmware(fmus.load(request.candidate())), ChangeManifest.NONE, FmuAdapters.brakeFirmware(fmus.load(request.baseline())));
    }

    @ExceptionHandler(FmuException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badFmu(FmuException e) {
        return Map.of("message", e.getMessage(), "blockedByPolicy", String.valueOf(e.blockedByPolicy()));
    }

    @GetMapping(path = "/runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        requireRun(id);
        return runs.subscribe(id);
    }

    @GetMapping("/runs/{id}")
    public GateReport report(@PathVariable String id) {
        requireRun(id);
        GateReport report = runs.report(id);
        if (report == null) {
            throw new ResponseStatusException(HttpStatus.ACCEPTED, "This run is still in progress");
        }
        return report;
    }

    @GetMapping("/carla")
    public CarlaService.Status carlaStatus() {
        return carla.status();
    }

    @PostMapping("/runs/{id}/recheck")
    public RecheckReport recheck(@PathVariable String id) {
        requireRun(id);
        GateReport report = runs.report(id);
        if (report == null) {
            throw new IllegalArgumentException("This run hasn't finished yet");
        }
        if (report.counterexamples().isEmpty()) {
            throw new IllegalArgumentException("This run has no failures to re-check");
        }
        return carla.recheck(runs.target(id), report);
    }

    @ExceptionHandler(BridgeException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> carlaUnavailable(BridgeException e) {
        return Map.of("message", e.getMessage());
    }

    @GetMapping("/campaign")
    public CampaignService.Status campaign() {
        return campaign.status();
    }

    @PostMapping("/campaign")
    public Map<String, Boolean> startCampaign() {
        return Map.of("started", campaign.start());
    }

    @GetMapping(path = "/campaign/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter campaignStream() {
        return campaign.subscribe();
    }

    private void requireRun(String id) {
        if (!runs.exists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No run with that id");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }
}
