package com.twinguard.catalog;

import com.twinguard.oracle.ChangeManifest;
import com.twinguard.sim.Firmware;
import java.util.List;
import java.util.Optional;

/** The demo's OTA candidates: one legitimate update, which must pass, and three with hidden bugs. */
public final class ReleaseCatalog {

    private static final List<Release> ALL = List.of(
            new Release("v2.2-media", "v2.2 Media Player Update",
                    "Adds lossless audio streaming. No braking code was touched.",
                    "Infotainment",
                    Firmware.approved().with(f -> f.infotainmentPriority = 0),
                    ChangeManifest.NONE),
            new Release("v2.1-comfort", "v2.1 Smoother Pre-Braking",
                    "Starts gentle pre-braking slightly earlier. This change is intended and declared.",
                    "ADAS",
                    Firmware.approved().with(f -> f.ttcPreBrakeS = 2.6),
                    new ChangeManifest(0.4, 0, 5.0,
                            "Pre-braking may begin up to 0.4 s earlier, and the stopping gap may shrink by up to 5 m")),
            new Release("v2.3-nuisance", "v2.3 Fewer False Alarms",
                    "Lowers the stopping-distance margin to reduce nuisance braking.",
                    "ADAS",
                    Firmware.approved().with(f -> f.stopMargin = 0.9),
                    ChangeManifest.NONE),
            new Release("v2.4-fast-radar", "v2.4 Faster Radar Response",
                    "Removes radar filtering so the car reacts more quickly.",
                    "ADAS",
                    Firmware.approved().with(f -> {
                        f.debounceCycles = 1;
                        f.filterWindow = 1;
                    }),
                    ChangeManifest.NONE));

    private ReleaseCatalog() {
    }

    public static List<Release> all() {
        return ALL;
    }

    public static Optional<Release> find(String id) {
        return ALL.stream().filter(r -> r.id().equals(id)).findFirst();
    }
}
