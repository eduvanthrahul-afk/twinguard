/*
 * Battery charge control, written in C and shipped as an FMU. It follows the same signal contract as
 * TwinGuard's battery feature. Compile-time values create the buggy version.
 */
#ifndef DERATE_TEMPERATURE
#define DERATE_TEMPERATURE 45.0
#endif
#ifndef CUTOFF_TEMPERATURE
#define CUTOFF_TEMPERATURE 55.0
#endif

#define TAPER_VOLTAGE 4.15
#define CUTOFF_VOLTAGE 4.22
#define TAPER_GAIN 0.2
#define COLD_TEMPERATURE 2.0
#define COLD_LIMIT 0.3
#define FULL_CURRENT 0.05
#define MAX_CURRENT 3.0
#define CONFIRM_SAMPLES 3
#define RAMP 0.05

#define N_INPUTS 3
#define N_OUTPUTS 2

typedef struct {
    double limit;
    double output;
    int over_voltage;
    int tapering;
    int faulted;
    int finished;
} State;

static void controller_init(State *s) {
    (void)s;
}

static double smaller(double a, double b) {
    return a < b ? a : b;
}

static void controller_step(State *s, const double *in, double *out) {
    double voltage = in[0];
    double temperature = in[1];
    double requested = in[2];

    s->over_voltage = voltage >= CUTOFF_VOLTAGE ? s->over_voltage + 1 : 0;
    if (s->over_voltage >= CONFIRM_SAMPLES || temperature >= CUTOFF_TEMPERATURE) {
        s->faulted = 1;
    }
    if (voltage > TAPER_VOLTAGE) {
        s->tapering = 1;
        s->limit -= TAPER_GAIN * (voltage - TAPER_VOLTAGE);
    } else {
        s->limit = smaller(s->limit + RAMP, smaller(MAX_CURRENT, requested));
    }
    if (s->limit < 0) {
        s->limit = 0;
    }

    double allowed = s->limit;
    if (temperature > DERATE_TEMPERATURE) {
        double share = (CUTOFF_TEMPERATURE - temperature) / (CUTOFF_TEMPERATURE - DERATE_TEMPERATURE);
        allowed *= share > 0 ? share : 0;
    }
    if (temperature < COLD_TEMPERATURE) {
        allowed = smaller(allowed, COLD_LIMIT);
    }
    if (s->tapering && s->limit < FULL_CURRENT) {
        s->finished = 1;
    }
    if (s->faulted || s->finished) {
        out[0] = 0;
        out[1] = 0;
        return;
    }
    if (allowed > s->output + RAMP) {
        allowed = s->output + RAMP;
    }
    s->output = allowed;
    out[0] = s->output;
    out[1] = 1;
}

#include "fmi2_wrapper.h"
