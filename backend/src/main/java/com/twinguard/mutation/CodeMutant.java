package com.twinguard.mutation;

import com.twinguard.sim.Firmware;

/** One real bug, created by rewriting a single instruction in the compiled braking code. */
public record CodeMutant(String id, String method, int line, String operator, String description, String sourceLine,
                         Firmware firmware) {
}
