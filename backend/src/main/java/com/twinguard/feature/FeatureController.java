package com.twinguard.feature;

/** The software under test for one feature. It can be our Java logic, a code mutant, or a third-party FMU. */
public interface FeatureController {

    /** Runs one control step. Values follow the order of the feature's input and output names. */
    double[] step(double[] inputs);
}
