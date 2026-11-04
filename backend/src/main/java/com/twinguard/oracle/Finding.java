package com.twinguard.oracle;

/** A confirmed safety problem seen in one scenario. */
public record Finding(Type type, Severity severity, String message) {

    public enum Severity { CRITICAL, HIGH, MEDIUM }

    public enum Type {
        SOFTWARE_CRASH(Severity.CRITICAL),
        SAFETY_VIOLATION(Severity.CRITICAL),
        NEW_COLLISION(Severity.CRITICAL),
        WORSE_IMPACT(Severity.CRITICAL),
        MISSED_BRAKE(Severity.CRITICAL),
        PHANTOM_BRAKE(Severity.CRITICAL),
        LATE_BRAKE(Severity.HIGH),
        WEAK_BRAKE(Severity.HIGH),
        REDUCED_MARGIN(Severity.MEDIUM),
        EARLY_BRAKE(Severity.MEDIUM);

        public final Severity severity;

        Type(Severity severity) {
            this.severity = severity;
        }
    }

    public static Finding of(Type type, String message) {
        return new Finding(type, type.severity, message);
    }
}
