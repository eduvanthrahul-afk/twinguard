package com.twinguard.feature;

import java.util.List;
import java.util.Optional;

/** The registered plugins. Adding a feature to TwinGuard means implementing VehicleFeature and listing it here. */
public final class Features {

    private static final List<VehicleFeature> ALL = List.of(
            new BatteryFeature(), new CruiseFeature(), new LaneFeature(), new PedestrianFeature());

    private Features() {
    }

    public static List<VehicleFeature> all() {
        return ALL;
    }

    public static Optional<VehicleFeature> find(String id) {
        return ALL.stream().filter(f -> f.id().equals(id)).findFirst();
    }
}
