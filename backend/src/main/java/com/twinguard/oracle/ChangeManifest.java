package com.twinguard.oracle;

/**
 * Differences the release is allowed to introduce, so that a deliberate change is not reported
 * as a regression. Fixed safety rules still apply and can never be waived here.
 */
public record ChangeManifest(double allowedEarlyS, double allowedLateS, double allowedGapLossM, String intent) {

    public static final ChangeManifest NONE = new ChangeManifest(0, 0, 0, "No behaviour change intended");
}
