package com.twinguard.catalog;

import com.twinguard.oracle.ChangeManifest;
import com.twinguard.sim.Firmware;

/** An OTA update waiting for a release decision. */
public record Release(String id, String title, String summary, String component, Firmware firmware,
                      ChangeManifest manifest) {
}
