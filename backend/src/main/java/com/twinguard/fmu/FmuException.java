package com.twinguard.fmu;

/** An FMU is invalid, doesn't match what's needed, or failed while running. */
public final class FmuException extends RuntimeException {

    private final boolean blockedByPolicy;

    public FmuException(String message) {
        super(message);
        blockedByPolicy = false;
    }

    public FmuException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public FmuException(String message, Throwable cause, boolean blockedByPolicy) {
        super(message, cause);
        this.blockedByPolicy = blockedByPolicy;
    }

    /** True if the operating system's security policy refused to load the FMU's native code. */
    public boolean blockedByPolicy() {
        return blockedByPolicy;
    }
}
