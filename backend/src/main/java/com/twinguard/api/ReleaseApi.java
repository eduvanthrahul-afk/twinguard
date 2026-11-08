package com.twinguard.api;

import com.twinguard.catalog.ReleasePackage;
import com.twinguard.feature.Features;
import com.twinguard.feature.VehicleFeature;
import com.twinguard.store.ReleaseStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Imports real OTA candidates as release packages, so they can be gated like the built-in ones. */
@RestController
@RequestMapping("/api/releases")
public class ReleaseApi {

    public record Imported(ReleasePackage pkg, Instant importedAt, List<String> changedSettings) {
    }

    public record FeatureSchema(String feature, String name, List<ReleasePackage.SettingInfo> settings,
                                Map<String, String> declarableChanges, boolean usesAllowances) {
    }

    private final ReleaseStore store;

    public ReleaseApi(ReleaseStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Imported> list() {
        return store.all().stream().map(s -> new Imported(s.pkg(), s.importedAt(), List.copyOf(s.pkg().overrides().keySet()))).toList();
    }

    /** Validates the package, proves it runs, and saves it. An imported package with the same id is replaced. */
    @PostMapping
    public ResponseEntity<Imported> importPackage(@RequestBody String body) {
        ReleasePackage pkg = ReleasePackage.parse(body).validated();
        boolean replaced = store.find(pkg.id()).isPresent();
        store.save(pkg);
        Imported view = new Imported(pkg, Instant.now(), List.copyOf(pkg.overrides().keySet()));
        return ResponseEntity.status(replaced ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable String id) {
        if (!store.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No imported release with that id");
        }
        return Map.of("deleted", true);
    }

    /** What each feature lets a package change, for the import form and for people writing packages. */
    @GetMapping("/schema")
    public List<FeatureSchema> schema() {
        List<FeatureSchema> out = new ArrayList<>();
        out.add(new FeatureSchema(ReleasePackage.BRAKING, "Collision braking", ReleasePackage.settingsFor(ReleasePackage.BRAKING), Map.of(), true));
        for (VehicleFeature f : Features.all()) {
            out.add(new FeatureSchema(f.id(), f.name(), ReleasePackage.settingsFor(f.id()), f.declarableChanges(), false));
        }
        return out;
    }

    /** A starting package for one feature. Its one setting is still at the v1 value, so it must be edited before import. */
    @GetMapping("/template")
    public Map<String, Object> template(@RequestParam(defaultValue = ReleasePackage.BRAKING) String feature) {
        if (!ReleasePackage.featureIds().contains(feature)) {
            throw new IllegalArgumentException("Unknown feature: " + feature);
        }
        ReleasePackage.SettingInfo first = ReleasePackage.settingsFor(feature).get(0);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("intent", "No behaviour change intended");
        if (ReleasePackage.BRAKING.equals(feature)) {
            manifest.put("allowedEarlyS", 0);
            manifest.put("allowedLateS", 0);
            manifest.put("allowedGapLossM", 0);
        } else {
            manifest.put("declaredChanges", List.of());
        }
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("format", ReleasePackage.FORMAT);
        pkg.put("id", "my-update-1.0");
        pkg.put("title", "My update 1.0");
        pkg.put("summary", "What this update changes, in one or two sentences.");
        pkg.put("component", ReleasePackage.BRAKING.equals(feature) ? "ADAS" : Features.find(feature).map(VehicleFeature::name).orElse(feature));
        pkg.put("feature", feature);
        pkg.put("changes", Map.of(first.key(), "BOOLEAN".equals(first.kind()) ? first.v1() == 0 : first.v1()));
        pkg.put("manifest", manifest);
        return pkg;
    }

    @ExceptionHandler(ReleasePackage.InvalidPackageException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalid(ReleasePackage.InvalidPackageException e) {
        return Map.of("message", e.getMessage(), "problems", e.problems());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }
}
