/*
 * Forward collision braking, written in C and shipped as an FMU, the way a supplier might deliver it.
 * TwinGuard treats this as a black box. Compile-time values create the buggy versions.
 */
#include <math.h>

#ifndef STOP_MARGIN
#define STOP_MARGIN 1.2
#endif
#ifndef DEBOUNCE_CYCLES
#define DEBOUNCE_CYCLES 3
#endif
#ifndef FILTER_WINDOW
#define FILTER_WINDOW 5
#endif

#define TTC_PRE 2.4
#define TTC_FULL 1.2
#define STOP_BUFFER 2.0
#define PRE_LEVEL 0.4
#define FULL_LEVEL 1.0
#define MIN_ACTIVE_KPH 5.0
#define MAX_ACTIVE_KPH 200.0
#define STALE_MS 250.0
#define G 9.81
#define DT 0.01

#define N_INPUTS 7
#define N_OUTPUTS 1

typedef struct {
    double window[FILTER_WINDOW];
    int count;
    int next;
    int seen;
    int confirmed;
    double filtered;
    int last_tick;
    int latched;
    int engaged;
} State;

static void controller_init(State *s) {
    s->filtered = HUGE_VAL;
    s->last_tick = -1;
}

static double median(const State *s) {
    double sorted[FILTER_WINDOW];
    for (int i = 0; i < s->count; i++) {
        double v = s->window[i];
        int j = i;
        while (j > 0 && sorted[j - 1] > v) {
            sorted[j] = sorted[j - 1];
            j--;
        }
        sorted[j] = v;
    }
    return sorted[s->count / 2];
}

static void on_sensor(State *s, int seen, double distance, int tick) {
    s->last_tick = tick;
    if (!seen) {
        s->seen = 0;
        s->confirmed = 0;
        s->count = 0;
        s->next = 0;
        s->filtered = HUGE_VAL;
        return;
    }
    s->seen++;
    if (s->seen >= DEBOUNCE_CYCLES) {
        s->confirmed = 1;
    }
    s->window[s->next] = distance;
    s->next = (s->next + 1) % FILTER_WINDOW;
    if (s->count < FILTER_WINDOW) {
        s->count++;
    }
    s->filtered = median(s);
}

static double decide(State *s, double speed, int tick, double friction) {
    double age = s->last_tick < 0 ? 0 : (tick - s->last_tick) * DT * 1000;
    double kph = speed * 3.6;
    if (s->latched) {
        if (speed <= 0.05) {
            s->latched = 0;
            return 0;
        }
        return FULL_LEVEL;
    }
    if (s->engaged && kph < MIN_ACTIVE_KPH) {
        s->latched = 1;
        return FULL_LEVEL;
    }
    if (!s->confirmed || age > STALE_MS || kph < MIN_ACTIVE_KPH || kph > MAX_ACTIVE_KPH || speed <= 0) {
        return 0;
    }
    double ttc = s->filtered / speed;
    double mu = friction < 0.05 ? 0.05 : friction;
    double stop = speed * speed / (2 * mu * G) * STOP_MARGIN + STOP_BUFFER;
    if (ttc < TTC_FULL || s->filtered < stop) {
        s->engaged = 1;
        s->latched = 1;
        return FULL_LEVEL;
    }
    if (ttc < TTC_PRE) {
        s->engaged = 1;
        return PRE_LEVEL;
    }
    return 0;
}

static void controller_step(State *s, const double *in, double *out) {
    if (in[0] > 0.5) {
        on_sensor(s, in[1] > 0.5, in[2], (int)in[3]);
    } else {
        out[0] = decide(s, in[5], (int)in[4], in[6]);
    }
}

#include "fmi2_wrapper.h"
