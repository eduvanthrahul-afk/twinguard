package com.twinguard.feature;

import com.twinguard.oracle.Finding;

/** A safety problem in one scenario. Rule-based findings can never be excused by a declared change. */
public record FeatureFinding(String type, String label, Finding.Severity severity, String message) {

    public static final String CRASH = "SOFTWARE_CRASH";

    public static FeatureFinding critical(String type, String label, String message) {
        return new FeatureFinding(type, label, Finding.Severity.CRITICAL, message);
    }

    public static FeatureFinding high(String type, String label, String message) {
        return new FeatureFinding(type, label, Finding.Severity.HIGH, message);
    }

    public static FeatureFinding medium(String type, String label, String message) {
        return new FeatureFinding(type, label, Finding.Severity.MEDIUM, message);
    }
}
