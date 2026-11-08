package com.twinguard.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.twinguard.feature.FeatureController;
import com.twinguard.feature.Features;
import com.twinguard.feature.VehicleFeature;
import com.twinguard.gate.ReleaseGate;
import com.twinguard.oracle.ChangeManifest;
import com.twinguard.sim.Firmware;
import com.twinguard.sim.Scenario;
import com.twinguard.sim.Simulator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * An OTA update described as a file, so a real candidate can be tested instead of a demo one.
 * It names the feature it changes, the settings it changes compared with approved v1, and any
 * behaviour change it declares. See RELEASE_PACKAGES.md for the format.
 */
public record ReleasePackage(String format, String id, String title, String summary, String component, String feature,
                             Map<String, Object> changes, Manifest manifest) {

    public static final String FORMAT = "twinguard-release/1";
    public static final String BRAKING = "braking";

    /**
     * What the update is allowed to do differently. The three allowances apply to collision braking,
     * and declaredChanges to the other features.
     */
    public record Manifest(String intent, Double allowedEarlyS, Double allowedLateS, Double allowedGapLossM, List<String> declaredChanges) {
    }

    /** The package could not be accepted. Each problem is one sentence a person can act on. */
    public static final class InvalidPackageException extends IllegalArgumentException {
        private final List<String> problems;

        public InvalidPackageException(List<String> problems) {
            super(problems.size() == 1 ? problems.get(0) : "The release package has " + problems.size() + " problems: " + String.join(" ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    /** A setting a package may change, for the template and the import form. */
    public record SettingInfo(String key, String label, String kind, double v1, double min, double max) {
    }

    private static final ObjectMapper STRICT = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final double MAX_ALLOWED_SHIFT_S = 2;
    private static final double MAX_ALLOWED_GAP_LOSS_M = 20;
    private static final long SMOKE_TIMEOUT_MS = 5000;
    private static final ExecutorService SMOKE = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "package-check");
        t.setDaemon(true);
        return t;
    });

    public static ReleasePackage parse(String json) {
        try {
            return STRICT.readValue(json, ReleasePackage.class);
        } catch (UnrecognizedPropertyException e) {
            throw new InvalidPackageException(List.of("Unknown field \"" + e.getPropertyName() + "\". Check its spelling against the template."));
        } catch (JsonProcessingException e) {
            throw new InvalidPackageException(List.of("This isn't a valid release package: " + e.getOriginalMessage()));
        }
    }

    public String toJson() {
        try {
            return STRICT.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean braking() {
        return BRAKING.equals(feature);
    }

    /** Checks every field, then runs both versions once to prove the package can be simulated. */
    public ReleasePackage validated() {
        List<String> problems = new ArrayList<>();
        if (format != null && !FORMAT.equals(format)) {
            problems.add("\"format\" must be \"" + FORMAT + "\" (or left out).");
        }
        if (id == null || !ID.matcher(id).matches()) {
            problems.add("\"id\" is required: up to 64 letters, digits, dots, dashes or underscores, starting with a letter or digit.");
        } else if (builtInIds().contains(id)) {
            problems.add("\"" + id + "\" is the id of a built-in release. Choose another id.");
        }
        if (title == null || title.isBlank() || title.length() > 120) {
            problems.add("\"title\" is required, up to 120 characters.");
        }
        if (summary != null && summary.length() > 500) {
            problems.add("\"summary\" can be up to 500 characters.");
        }
        if (component != null && component.length() > 60) {
            problems.add("\"component\" can be up to 60 characters.");
        }
        Optional<VehicleFeature> plugin = Features.find(feature == null ? "" : feature);
        if (!braking() && plugin.isEmpty()) {
            problems.add("\"feature\" must be one of: " + String.join(", ", featureIds()) + ".");
            throw new InvalidPackageException(problems);
        }
        if (changes == null || changes.isEmpty()) {
            problems.add("\"changes\" must list at least one setting that differs from v1.");
        } else {
            Map<String, Double> numeric = numericChanges(problems);
            if (problems.isEmpty() && settingsUnchanged(numeric)) {
                problems.add("Every value in \"changes\" equals approved v1, so there is nothing to test.");
            }
        }
        checkManifest(plugin.orElse(null), problems);
        if (!problems.isEmpty()) {
            throw new InvalidPackageException(problems);
        }
        smokeTest();
        return this;
    }

    private Map<String, Double> numericChanges(List<String> problems) {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, SettingInfo> allowed = new LinkedHashMap<>();
        settingsFor(feature).forEach(s -> allowed.put(s.key(), s));
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            SettingInfo s = allowed.get(e.getKey());
            if (s == null) {
                problems.add("\"" + e.getKey() + "\" isn't a setting of " + feature + ". Valid settings: " + String.join(", ", allowed.keySet()) + ".");
                continue;
            }
            boolean bool = "BOOLEAN".equals(s.kind());
            Object raw = e.getValue();
            Double v = null;
            if (bool && raw instanceof Boolean b) {
                v = b ? 1.0 : 0.0;
            } else if (!bool && raw instanceof Number n) {
                v = n.doubleValue();
            }
            if (v == null) {
                problems.add("\"" + e.getKey() + "\" must be " + (bool ? "true or false." : "a number."));
            } else if (!Double.isFinite(v) || v < s.min() || v > s.max()) {
                problems.add("\"" + e.getKey() + "\" must be between " + fmt(s.min()) + " and " + fmt(s.max()) + ".");
            } else if ("INTEGER".equals(s.kind()) && v != Math.rint(v)) {
                problems.add("\"" + e.getKey() + "\" must be a whole number.");
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    private boolean settingsUnchanged(Map<String, Double> numeric) {
        Map<String, Double> v1 = new LinkedHashMap<>();
        settingsFor(feature).forEach(s -> v1.put(s.key(), s.v1()));
        return numeric.entrySet().stream().allMatch(e -> Double.compare(v1.get(e.getKey()), e.getValue()) == 0);
    }

    private void checkManifest(VehicleFeature plugin, List<String> problems) {
        if (manifest == null) {
            return;
        }
        if (manifest.intent() != null && manifest.intent().length() > 300) {
            problems.add("\"manifest.intent\" can be up to 300 characters.");
        }
        boolean declares = false;
        if (braking()) {
            if (manifest.declaredChanges() != null && !manifest.declaredChanges().isEmpty()) {
                problems.add("Collision braking packages declare changes with allowedEarlyS, allowedLateS and allowedGapLossM, not declaredChanges.");
            }
            declares |= allowance("allowedEarlyS", manifest.allowedEarlyS(), MAX_ALLOWED_SHIFT_S, problems);
            declares |= allowance("allowedLateS", manifest.allowedLateS(), MAX_ALLOWED_SHIFT_S, problems);
            declares |= allowance("allowedGapLossM", manifest.allowedGapLossM(), MAX_ALLOWED_GAP_LOSS_M, problems);
        } else {
            if (manifest.allowedEarlyS() != null || manifest.allowedLateS() != null || manifest.allowedGapLossM() != null) {
                problems.add("allowedEarlyS, allowedLateS and allowedGapLossM only apply to collision braking. Use declaredChanges for " + feature + ".");
            }
            for (String change : manifest.declaredChanges() == null ? List.<String>of() : manifest.declaredChanges()) {
                if (!plugin.declarableChanges().containsKey(change)) {
                    problems.add(plugin.declarableChanges().isEmpty()
                            ? feature + " has no declarable changes, so \"" + change + "\" can't be declared."
                            : "\"" + change + "\" can't be declared for " + feature + ". Choose from: " + String.join(", ", plugin.declarableChanges().keySet()) + ".");
                }
                declares = true;
            }
        }
        if (declares && (manifest.intent() == null || manifest.intent().isBlank())) {
            problems.add("A package that declares a behaviour change must explain it in \"manifest.intent\".");
        }
    }

    private static boolean allowance(String name, Double value, double max, List<String> problems) {
        if (value == null) {
            return false;
        }
        if (!Double.isFinite(value) || value < 0 || value > max) {
            problems.add("\"manifest." + name + "\" must be between 0 and " + fmt(max) + ".");
            return false;
        }
        return value > 0;
    }

    /** Runs one ordinary scenario on the candidate, so a package the simulator can't run is rejected now, not mid-test. */
    private void smokeTest() {
        Future<?> check = SMOKE.submit(() -> {
            if (braking()) {
                Simulator.run(firmware(), new Scenario(Scenario.Type.OBSTACLE, 80, 60, 0.8, 0.3, 0.5, 1), false);
            } else {
                VehicleFeature f = Features.find(feature).orElseThrow();
                FeatureController controller = f.logic(f.settingsWith(overrides()));
                f.simulate(controller, f.nominal(), 1, false);
            }
        });
        try {
            check.get(SMOKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            check.cancel(true);
            throw new InvalidPackageException(List.of("The simulator couldn't finish one scenario with these settings in "
                    + SMOKE_TIMEOUT_MS / 1000 + " s. Check the values in \"changes\"."));
        } catch (ExecutionException e) {
            throw new InvalidPackageException(List.of("The simulator couldn't run these settings: " + e.getCause().getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking the package", e);
        }
    }

    /** The settings as numbers. Booleans become 1 or 0. Call only on a validated package. */
    public Map<String, Double> overrides() {
        Map<String, Double> out = new LinkedHashMap<>();
        changes.forEach((k, v) -> out.put(k, v instanceof Boolean b ? (b ? 1.0 : 0.0) : ((Number) v).doubleValue()));
        return out;
    }

    public Firmware firmware() {
        Firmware firmware = Firmware.approved();
        overrides().forEach((k, v) -> BrakingSettings.write(firmware, BrakingSettings.find(k).orElseThrow(), v));
        return firmware;
    }

    public String intent() {
        return manifest == null || manifest.intent() == null || manifest.intent().isBlank() ? "No behaviour change declared" : manifest.intent();
    }

    public ChangeManifest changeManifest() {
        if (manifest == null) {
            return ChangeManifest.NONE;
        }
        return new ChangeManifest(orZero(manifest.allowedEarlyS()), orZero(manifest.allowedLateS()), orZero(manifest.allowedGapLossM()), intent());
    }

    public Set<String> declaredChanges() {
        return manifest == null || manifest.declaredChanges() == null ? Set.of() : new LinkedHashSet<>(manifest.declaredChanges());
    }

    public String componentOrDefault() {
        if (component != null && !component.isBlank()) {
            return component;
        }
        return braking() ? "ADAS" : Features.find(feature).map(VehicleFeature::name).orElse(feature);
    }

    public String summaryOrDefault() {
        return summary == null || summary.isBlank() ? "Imported release package" : summary;
    }

    public ReleaseGate.Target brakingTarget() {
        return new ReleaseGate.Target(id, title, "release", firmware(), changeManifest());
    }

    public VehicleFeature.Release featureRelease() {
        return new VehicleFeature.Release(id, title, summaryOrDefault(), overrides(), declaredChanges(), intent());
    }

    public static List<SettingInfo> settingsFor(String feature) {
        if (BRAKING.equals(feature)) {
            return BrakingSettings.ALL.stream()
                    .map(b -> new SettingInfo(b.key(), b.label(), b.kind().name(), BrakingSettings.approved(b), b.min(), b.max())).toList();
        }
        VehicleFeature f = Features.find(feature).orElseThrow(() -> new IllegalArgumentException("Unknown feature: " + feature));
        List<SettingInfo> out = new ArrayList<>();
        f.defaultSettings().forEach((k, v1) -> {
            // Plugin features don't publish ranges, so a package may move a setting up to tenfold either way.
            double lo = v1 > 0 ? v1 / 10 : v1 < 0 ? v1 * 10 : -10;
            double hi = v1 > 0 ? v1 * 10 : v1 < 0 ? v1 / 10 : 10;
            out.add(new SettingInfo(k, k, "NUMBER", v1, round(lo), round(hi)));
        });
        return out;
    }

    public static List<String> featureIds() {
        List<String> ids = new ArrayList<>();
        ids.add(BRAKING);
        Features.all().forEach(f -> ids.add(f.id()));
        return ids;
    }

    private static Set<String> builtInIds() {
        Set<String> ids = new LinkedHashSet<>();
        ReleaseCatalog.all().forEach(r -> ids.add(r.id()));
        Features.all().forEach(f -> f.releases().forEach(r -> ids.add(r.id())));
        return ids;
    }

    private static double orZero(Double v) {
        return v == null ? 0 : v;
    }

    private static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
