package com.twinguard.carla;

/** The CARLA bridge is unreachable or reported an error. */
public final class BridgeException extends RuntimeException {

    public BridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
