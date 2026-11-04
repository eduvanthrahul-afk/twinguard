package com.twinguard.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.twinguard.carla.BridgeClient;
import com.twinguard.carla.BridgeException;
import com.twinguard.carla.HighFidelityRecheck;
import com.twinguard.carla.RecheckReport;
import com.twinguard.campaign.CampaignReport;
import com.twinguard.campaign.MutationCampaign;
import com.twinguard.feature.FeatureController;
import com.twinguard.feature.FeatureGate;
import com.twinguard.feature.FeatureReport;
import com.twinguard.feature.Features;
import com.twinguard.feature.VehicleFeature;
import com.twinguard.fmu.FmuAdapters;
import com.twinguard.fmu.FmuException;
import com.twinguard.fmu.FmuFile;
import com.twinguard.gate.GateReport;
import com.twinguard.gate.GateTargets;
import com.twinguard.gate.ReleaseGate;
import com.twinguard.mutation.CodeMutationCampaign;
import com.twinguard.mutation.CodeMutationReport;
import com.twinguard.oracle.ChangeManifest;
import com.twinguard.testgen.ScenarioSearch;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The CI entry point. Exit codes: 0 approve, 1 block, 2 needs human review, 3 bad usage.
 *
 *   java -jar twinguard.jar gate --release=v2.2-media --budget=1500 --report=report.json --html=report.html
 *   java -jar twinguard.jar campaign --report=campaign.json --min-score=70
 *   java -jar twinguard.jar mutation --report=mutation.json
 */
public final class GateCli {

    public static final Set<String> COMMANDS = Set.of("gate", "campaign", "mutation", "feature");

    private static final int USAGE_ERROR = 3;
    private static final long CI_RNG_SEED = 20_260_101L;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private GateCli() {
    }

    public static int run(String command, String[] args) throws Exception {
        Map<String, String> options = parse(args);
        return switch (command) {
            case "gate" -> gate(options);
            case "campaign" -> campaign(options);
            case "mutation" -> mutation(options);
            case "feature" -> feature(options);
            default -> USAGE_ERROR;
        };
    }

    /** Tests a release of a plugin feature, or one FMU against another. */
    private static int feature(Map<String, String> o) throws Exception {
        VehicleFeature feature = Features.find(o.getOrDefault("id", "")).orElse(null);
        if (feature == null) {
            System.err.println("Usage: feature --id=<" + String.join("|", Features.all().stream().map(VehicleFeature::id).toList()) + "> --release=<id> | --fmu-baseline=<file> --fmu-candidate=<file> [--budget=1500] [--report=file.json]");
            return USAGE_ERROR;
        }
        Supplier<FeatureController> baseline = feature.source(Map.of());
        Supplier<FeatureController> candidate;
        Set<String> declared = Set.of();
        String id;
        String title;
        try {
            if (o.containsKey("fmu-candidate")) {
                baseline = FmuAdapters.forFeature(FmuFile.load(Path.of(o.getOrDefault("fmu-baseline", ""))), feature);
                candidate = FmuAdapters.forFeature(FmuFile.load(Path.of(o.get("fmu-candidate"))), feature);
                id = o.get("fmu-candidate");
                title = id;
            } else {
                VehicleFeature.Release r = feature.releases().stream().filter(x -> x.id().equals(o.get("release"))).findFirst().orElse(null);
                if (r == null) {
                    System.err.println("Unknown release. Choose from: " + String.join(", ", feature.releases().stream().map(VehicleFeature.Release::id).toList()));
                    return USAGE_ERROR;
                }
                candidate = feature.source(r.overrides());
                declared = r.declaredChanges();
                id = r.id();
                title = r.title();
            }
        } catch (FmuException e) {
            System.err.println(e.getMessage());
            return USAGE_ERROR;
        }
        FeatureReport report = FeatureGate.run(feature, id, title, List.of(), baseline, candidate, declared,
                Integer.parseInt(o.getOrDefault("budget", "1500")), CI_RNG_SEED, null);
        System.out.printf("TwinGuard gate: %s, %s%n", feature.name(), report.targetTitle());
        System.out.printf("Conventional suite: %d/%d passed%n", report.conventionalPassed(), report.conventionalTotal());
        System.out.printf("Scenarios:      %d tested, %d failing%n", report.evaluations(), report.failingScenarios());
        report.counterexamples().forEach(c -> System.out.printf("  [%s] %s%n", c.primary().type(), c.primary().message()));
        System.out.printf("Decision:       %s. %s%n", report.verdict(), report.reason());
        if (o.containsKey("report")) {
            JSON.writeValue(new File(o.get("report")), report);
        }
        return switch (report.verdict()) {
            case APPROVE -> 0;
            case BLOCK -> 1;
            case REVIEW -> 2;
        };
    }

    private static int mutation(Map<String, String> o) throws Exception {
        CodeMutationReport report = new CodeMutationCampaign(CodeMutationCampaign.DEFAULT_TRIALS, CodeMutationCampaign.DEFAULT_BUDGET)
                .run((phase, progress) -> { });
        System.out.printf("Code mutants caught: %d of %d (%.1f%%, 95%% range %.1f to %.1f)%n", report.overall().detected(),
                report.overall().total(), report.overall().percent(), report.overall().lower95(), report.overall().upper95());
        System.out.printf("Never changed braking in any test: %d%n", report.noBehaviourChangeMutants());
        System.out.printf("Random testing, same budget: %d of %d%n", report.randomBaseline().detected(), report.randomBaseline().total());
        if (o.containsKey("report")) {
            JSON.writeValue(new File(o.get("report")), report);
        }
        double minimum = Double.parseDouble(o.getOrDefault("min-score", "0"));
        return report.overall().percent() >= minimum ? 0 : 1;
    }

    private static int gate(Map<String, String> o) throws Exception {
        ReleaseGate.Target target;
        try {
            if (o.containsKey("fmu-candidate")) {
                target = new ReleaseGate.Target(o.get("fmu-candidate"), o.get("fmu-candidate"), "fmu",
                        FmuAdapters.brakeFirmware(FmuFile.load(Path.of(o.get("fmu-candidate")))), ChangeManifest.NONE,
                        FmuAdapters.brakeFirmware(FmuFile.load(Path.of(o.getOrDefault("fmu-baseline", "")))));
            } else if (o.containsKey("release")) {
                target = GateTargets.release(o.get("release"));
            } else if (o.containsKey("fault")) {
                target = GateTargets.fault(o.get("fault"));
            } else {
                System.err.println("Usage: gate --release=<id> | --fault=<id> | --fmu-baseline=<file> --fmu-candidate=<file> [--budget=1500] [--report=file.json] [--html=file.html]");
                return USAGE_ERROR;
            }
        } catch (IllegalArgumentException | FmuException e) {
            System.err.println(e.getMessage());
            return USAGE_ERROR;
        }

        int budget = Integer.parseInt(o.getOrDefault("budget", "1500"));
        GateReport report = ReleaseGate.run(target, budget, ScenarioSearch.Strategy.CHANGE_AWARE, CI_RNG_SEED, null);

        System.out.printf("TwinGuard gate: %s%n", report.targetTitle());
        System.out.printf("Legacy suite:   %d/%d passed%n", report.legacy().passed(), report.legacy().total());
        System.out.printf("Scenarios:      %d tested, %d failing, %.1f%% of coverage map%n",
                report.evaluations(), report.failingScenarios(), report.coveragePercent());
        report.counterexamples().forEach(c -> System.out.printf("  [%s] %s%n      at %.0f km/h, %.0f m, friction %.2f, infotainment load %.0f%%%n",
                c.primary().type(), c.primary().message(), c.scenario().speedKph(), c.scenario().distanceM(),
                c.scenario().friction(), c.scenario().infotainmentLoad() * 100));
        System.out.printf("Decision:       %s. %s%n", report.verdict(), report.reason());

        if (o.containsKey("report")) {
            JSON.writeValue(new File(o.get("report")), report);
        }
        if (o.containsKey("html")) {
            Files.writeString(Path.of(o.get("html")), EvidenceHtml.render(report), StandardCharsets.UTF_8);
        }
        if ("carla".equals(o.get("recheck")) && !report.counterexamples().isEmpty()) {
            recheck(o, target, report);
        }
        return switch (report.verdict()) {
            case APPROVE -> 0;
            case BLOCK -> 1;
            case REVIEW -> 2;
        };
    }

    private static String outcome(GateReport.Metrics m) {
        return m.collision() ? String.format("impact %.0f km/h", m.impactKph()) : String.format("gap %.1f m", m.minGapM());
    }

    /** Reports whether failures reproduce on CARLA. The release decision still comes from the gate above. */
    private static void recheck(Map<String, String> o, ReleaseGate.Target target, GateReport report) throws Exception {
        String[] address = o.getOrDefault("bridge", BridgeClient.DEFAULT_HOST + ":" + BridgeClient.DEFAULT_PORT).split(":");
        try (BridgeClient bridge = new BridgeClient(address[0], Integer.parseInt(address[1]))) {
            RecheckReport result = HighFidelityRecheck.run(bridge, target, report);
            System.out.printf("%nRe-check on %s (%s, version %s, %s)%s%n", result.backend(), result.map(), result.version(),
                    result.calibrated() ? "calibrated" : "NOT calibrated", result.testDouble() ? ": TEST DOUBLE, NOT REAL CARLA" : "");
            result.items().forEach(i -> System.out.printf("  %-18s %-14s friction %.2f%s | v1 %s | v2 %s | seen %s | control %s%n", i.findingType(),
                    i.reproduced() ? "reproduced" : i.inconclusive() ? "inconclusive" : "NOT reproduced", i.scenario().friction(), i.withinCalibration() ? "" : " (outside calibration)",
                    outcome(i.v1()), outcome(i.v2()), i.findingsSeen(), i.controlFindings()));
            System.out.printf("Reproduced:     %d of %d (%d inconclusive), or %d of %d within the calibrated range, in %.1f s%n", result.reproduced(), result.checked(),
                    result.inconclusive(), result.reproducedWithinCalibration(), result.withinCalibration(), result.computeMs() / 1000.0);
            System.out.printf("Control:        v1 against v1 raised findings on %d of %d scenarios%n", result.controlFalseAlarms(), result.checked());
            if (o.containsKey("recheck-report")) {
                JSON.writeValue(new File(o.get("recheck-report")), result);
            }
        } catch (BridgeException e) {
            System.err.println("Re-check skipped: " + e.getMessage());
        }
    }

    private static int campaign(Map<String, String> o) throws Exception {
        CampaignReport report = new MutationCampaign(MutationCampaign.DEFAULT_TRIALS, MutationCampaign.DEFAULT_BUDGET)
                .run((phase, progress) -> { });
        System.out.printf("Faults caught:  %d of %d (%.1f%%, 95%% range %.1f to %.1f)%n", report.overall().detected(),
                report.overall().total(), report.overall().percent(), report.overall().lower95(), report.overall().upper95());
        System.out.printf("Held-out:       %d of %d (%.1f%%)%n", report.holdout().detected(), report.holdout().total(), report.holdout().percent());
        if (o.containsKey("report")) {
            JSON.writeValue(new File(o.get("report")), report);
        }
        double minimum = Double.parseDouble(o.getOrDefault("min-score", "0"));
        return report.overall().percent() >= minimum ? 0 : 1;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                int eq = arg.indexOf('=');
                out.put(arg.substring(2, eq), arg.substring(eq + 1));
            }
        }
        return out;
    }
}
