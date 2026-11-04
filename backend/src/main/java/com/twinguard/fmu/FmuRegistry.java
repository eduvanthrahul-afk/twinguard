package com.twinguard.fmu;

import com.twinguard.feature.Features;
import com.twinguard.feature.VehicleFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The FMUs placed in TwinGuard's folder. Loading an FMU runs its native code, so there's deliberately
 * no upload. A file has to be put in the folder by someone with access to this machine.
 */
public final class FmuRegistry {

    public static final String BRAKING = "braking";

    public record Entry(String file, String modelName, String description, String feature, boolean usable, String problem) {
    }

    private final Path folder;
    private final Map<String, FmuFile> loaded = new ConcurrentHashMap<>();

    public FmuRegistry(Path folder) {
        this.folder = folder;
    }

    public static Path defaultFolder() {
        String configured = System.getenv("TWINGUARD_FMU_DIR");
        return Path.of(configured != null && !configured.isBlank() ? configured : "fmus");
    }

    public Path folder() {
        return folder;
    }

    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return out;
        }
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(p -> p.getFileName().toString().endsWith(".fmu")).sorted(Comparator.naturalOrder()).forEach(p -> out.add(describe(p)));
        } catch (IOException e) {
            throw new FmuException("Couldn't read the FMU folder: " + e.getMessage(), e);
        }
        return out;
    }

    private static Entry describe(Path file) {
        String name = file.getFileName().toString();
        try {
            FmuFile.Description d = FmuFile.describe(file);
            String feature = featureOf(d);
            return new Entry(name, d.modelName(), d.description(), feature, feature != null,
                    feature == null ? "Its signals don't match any feature's contract" : null);
        } catch (FmuException e) {
            return new Entry(name, name, "", null, false, e.getMessage());
        }
    }

    private static String featureOf(FmuFile.Description d) {
        if (d.inputs().keySet().containsAll(FmuAdapters.BRAKE_INPUTS) && d.outputs().keySet().containsAll(FmuAdapters.BRAKE_OUTPUTS)) {
            return BRAKING;
        }
        for (VehicleFeature f : Features.all()) {
            if (d.inputs().keySet().containsAll(f.inputNames()) && d.outputs().keySet().containsAll(f.outputNames())) {
                return f.id();
            }
        }
        return null;
    }

    /** Loads a file by the exact name shown in the listing, which rules out any path outside the folder. */
    public FmuFile load(String file) {
        boolean listed = list().stream().anyMatch(e -> e.file().equals(file));
        if (!listed) {
            throw new FmuException("There's no FMU called " + file + " in the FMU folder");
        }
        return loaded.computeIfAbsent(file, f -> FmuFile.load(folder.resolve(f)));
    }
}
