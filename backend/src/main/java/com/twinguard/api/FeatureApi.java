package com.twinguard.api;

import com.twinguard.feature.FeatureController;
import com.twinguard.feature.FeatureMutants;
import com.twinguard.feature.FeatureReport;
import com.twinguard.feature.Features;
import com.twinguard.feature.VehicleFeature;
import com.twinguard.fmu.FmuAdapters;
import com.twinguard.fmu.FmuException;
import com.twinguard.fmu.FmuRegistry;
import com.twinguard.mutation.CodeMutator;
import com.twinguard.catalog.ReleasePackage;
import com.twinguard.store.ReleaseStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
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

/** The dashboard's view of the plugin features and of third-party FMUs. */
@RestController
@RequestMapping("/api")
public class FeatureApi {

    private static final int DEFAULT_BUDGET = 1500;
    private static final int MIN_BUDGET = 100;
    private static final int MAX_BUDGET = 5000;

    public record ReleaseView(String id, String title, String summary, List<String> changedSettings, String intent, boolean imported) {
    }

    public record FaultView(String id, String faultClass, String title) {
    }

    public record MutantView(String id, String method, int line, String operator, String description, String sourceLine) {
    }

    public record FeatureView(String id, String name, String summary, String limitations, List<VehicleFeature.Param> params,
                              int coverageX, int coverageY, List<VehicleFeature.Channel> channels, List<String> inputNames, List<String> outputNames,
                              double stepSeconds, List<ReleaseView> releases, List<FaultView> faults, List<MutantView> mutants) {
    }

    public record RunRequest(String kind, String id, String baseline, String candidate, Integer budget) {
    }

    private final FeatureRunService runs;
    private final CampaignService campaign;
    private final FmuRegistry fmus;
    private final ReleaseStore imported;

    public FeatureApi(FeatureRunService runs, CampaignService campaign, FmuRegistry fmus, ReleaseStore imported) {
        this.runs = runs;
        this.campaign = campaign;
        this.fmus = fmus;
        this.imported = imported;
    }

    @GetMapping("/features")
    public List<FeatureView> features() {
        return Features.all().stream().map(this::view).toList();
    }

    private FeatureView view(VehicleFeature f) {
        List<ReleaseView> releases = new ArrayList<>(f.releases().stream()
                .map(r -> new ReleaseView(r.id(), r.title(), r.summary(), List.copyOf(r.overrides().keySet()), r.intent(), false)).toList());
        for (ReleasePackage p : imported.forFeature(f.id())) {
            releases.add(new ReleaseView(p.id(), p.title(), p.summaryOrDefault(), List.copyOf(p.overrides().keySet()), p.intent(), true));
        }
        return new FeatureView(f.id(), f.name(), f.summary(), f.limitations(), f.params(), f.coverageX(), f.coverageY(), f.channels(), f.inputNames(), f.outputNames(), f.stepSeconds(),
                releases,
                f.faults().stream().map(x -> new FaultView(x.id(), x.faultClass(), x.title())).toList(),
                FeatureMutants.of(f).stream().map(m -> new MutantView(m.id(), m.method(), m.line(), m.operator(), m.description(), m.sourceLine())).toList());
    }

    @GetMapping("/fmus")
    public Map<String, Object> listFmus() {
        return Map.of("folder", fmus.folder().toAbsolutePath().normalize().toString(), "files", fmus.list());
    }

    @PostMapping("/features/{id}/runs")
    public Map<String, String> start(@PathVariable String id, @RequestBody RunRequest request) {
        VehicleFeature feature = Features.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such feature"));
        int budget = request.budget() == null ? DEFAULT_BUDGET : request.budget();
        if (budget < MIN_BUDGET || budget > MAX_BUDGET) {
            throw new IllegalArgumentException("Budget must be between " + MIN_BUDGET + " and " + MAX_BUDGET);
        }
        if (request.kind() == null) {
            throw new IllegalArgumentException("A run needs a kind: release, fault, mutant or fmu");
        }
        Supplier<FeatureController> approved = feature.source(Map.of());
        FeatureRunService.Request run = switch (request.kind()) {
            case "release" -> {
                VehicleFeature.Release r = feature.releases().stream().filter(x -> x.id().equals(request.id())).findFirst()
                        .or(() -> imported.find(feature.id(), request.id()).map(ReleasePackage::featureRelease))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown release: " + request.id()));
                yield new FeatureRunService.Request(feature, r.id(), r.title(), List.copyOf(r.overrides().keySet()), approved, feature.source(r.overrides()), r.declaredChanges(), budget);
            }
            case "fault" -> {
                VehicleFeature.SettingFault f = feature.faults().stream().filter(x -> x.id().equals(request.id())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown fault: " + request.id()));
                yield new FeatureRunService.Request(feature, f.id(), "Injected fault: " + f.title(), List.copyOf(f.overrides().keySet()), approved, feature.source(f.overrides()), Set.of(), budget);
            }
            case "mutant" -> {
                CodeMutator.Generated m = FeatureMutants.find(feature, request.id())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown code mutant: " + request.id()));
                yield new FeatureRunService.Request(feature, m.id(), "Code mutant " + m.id(), List.of(), approved, FeatureMutants.source(feature, m), Set.of(), budget);
            }
            case "fmu" -> {
                if (request.candidate() == null || request.baseline() == null) {
                    throw new IllegalArgumentException("An FMU run needs a baseline and a candidate file");
                }
                yield new FeatureRunService.Request(feature, request.candidate(), request.candidate() + " vs " + request.baseline(), List.of(),
                        FmuAdapters.forFeature(fmus.load(request.baseline()), feature), FmuAdapters.forFeature(fmus.load(request.candidate()), feature), Set.of(), budget);
            }
            default -> throw new IllegalArgumentException("Unknown run kind: " + request.kind());
        };
        String summary = switch (request.kind()) {
            case "release" -> feature.releases().stream().filter(x -> x.id().equals(request.id())).map(VehicleFeature.Release::summary).findFirst()
                    .or(() -> imported.find(feature.id(), request.id()).map(ReleasePackage::summaryOrDefault)).orElse("");
            case "fmu" -> "Third-party FMU, run against this feature's safety rules.";
            case "mutant" -> "A real code bug, made by rewriting this feature's compiled logic.";
            default -> "A designed fault, injected into this feature's settings.";
        };
        return Map.of("runId", runs.start(run, request, summary));
    }

    @GetMapping(path = "/feature-runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        require(id);
        return runs.subscribe(id);
    }

    @GetMapping("/feature-runs/{id}")
    public FeatureReport report(@PathVariable String id) {
        require(id);
        FeatureReport report = runs.report(id);
        if (report == null) {
            throw new ResponseStatusException(HttpStatus.ACCEPTED, "This run is still in progress");
        }
        return report;
    }

    private void require(String id) {
        if (!runs.exists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No run with that id");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }

    @ExceptionHandler(FmuException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badFmu(FmuException e) {
        return Map.of("message", e.getMessage(), "blockedByPolicy", String.valueOf(e.blockedByPolicy()));
    }
}
