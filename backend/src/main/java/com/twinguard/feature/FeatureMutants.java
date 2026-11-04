package com.twinguard.feature;

import com.twinguard.mutation.CodeMutator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Real code bugs for a plugin, created by rewriting its compiled logic class. */
public final class FeatureMutants {

    private static final Map<String, List<CodeMutator.Generated>> CACHE = new ConcurrentHashMap<>();

    private FeatureMutants() {
    }

    public static List<CodeMutator.Generated> of(VehicleFeature feature) {
        return CACHE.computeIfAbsent(feature.id(), id -> CodeMutator.generate(feature.logicClass(), Map.class));
    }

    public static Optional<CodeMutator.Generated> find(VehicleFeature feature, String id) {
        return of(feature).stream().filter(m -> m.id().equals(id)).findFirst();
    }

    public static Supplier<FeatureController> source(VehicleFeature feature, CodeMutator.Generated mutant) {
        Map<String, Double> settings = feature.defaultSettings();
        return () -> (FeatureController) CodeMutator.instantiate(mutant.constructor(), settings);
    }
}
