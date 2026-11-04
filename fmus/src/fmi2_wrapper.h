/*
 * A minimal FMI 2.0 Co-Simulation wrapper. A controller defines N_INPUTS, N_OUTPUTS, a State type,
 * controller_init, and controller_step, then includes this file. Value references are the input
 * positions, followed by the output positions.
 */
#include <stdlib.h>
#include <string.h>

#define FMI2_EXPORT __declspec(dllexport)

typedef void *fmi2Component;
typedef unsigned int fmi2ValueReference;
typedef double fmi2Real;
typedef int fmi2Boolean;
typedef int fmi2Integer;
typedef const char *fmi2String;
typedef enum { fmi2OK, fmi2Warning, fmi2Discard, fmi2Error, fmi2Fatal, fmi2Pending } fmi2Status;

typedef struct {
    double inputs[N_INPUTS];
    double outputs[N_OUTPUTS];
    State state;
} Instance;

FMI2_EXPORT fmi2Component fmi2Instantiate(fmi2String name, int type, fmi2String guid, fmi2String resources,
                                          const void *callbacks, fmi2Boolean visible, fmi2Boolean logging) {
    (void)name; (void)type; (void)resources; (void)callbacks; (void)visible; (void)logging;
    if (guid == NULL || strcmp(guid, FMU_GUID) != 0) {
        return NULL;
    }
    Instance *instance = (Instance *)calloc(1, sizeof(Instance));
    if (instance != NULL) {
        controller_init(&instance->state);
    }
    return instance;
}

FMI2_EXPORT fmi2Status fmi2SetupExperiment(fmi2Component c, fmi2Boolean toleranceDefined, fmi2Real tolerance,
                                           fmi2Real start, fmi2Boolean stopDefined, fmi2Real stop) {
    (void)c; (void)toleranceDefined; (void)tolerance; (void)start; (void)stopDefined; (void)stop;
    return fmi2OK;
}

FMI2_EXPORT fmi2Status fmi2EnterInitializationMode(fmi2Component c) { (void)c; return fmi2OK; }
FMI2_EXPORT fmi2Status fmi2ExitInitializationMode(fmi2Component c) { (void)c; return fmi2OK; }
FMI2_EXPORT fmi2Status fmi2Terminate(fmi2Component c) { (void)c; return fmi2OK; }
FMI2_EXPORT void fmi2FreeInstance(fmi2Component c) { free(c); }

FMI2_EXPORT fmi2Status fmi2SetReal(fmi2Component c, const fmi2ValueReference references[], size_t count, const fmi2Real values[]) {
    Instance *instance = (Instance *)c;
    for (size_t i = 0; i < count; i++) {
        if (references[i] >= N_INPUTS) {
            return fmi2Error;
        }
        instance->inputs[references[i]] = values[i];
    }
    return fmi2OK;
}

FMI2_EXPORT fmi2Status fmi2GetReal(fmi2Component c, const fmi2ValueReference references[], size_t count, fmi2Real values[]) {
    Instance *instance = (Instance *)c;
    for (size_t i = 0; i < count; i++) {
        fmi2ValueReference r = references[i];
        if (r >= N_INPUTS + N_OUTPUTS) {
            return fmi2Error;
        }
        values[i] = r < N_INPUTS ? instance->inputs[r] : instance->outputs[r - N_INPUTS];
    }
    return fmi2OK;
}

FMI2_EXPORT fmi2Status fmi2DoStep(fmi2Component c, fmi2Real time, fmi2Real step, fmi2Boolean noSetPrior) {
    Instance *instance = (Instance *)c;
    (void)time; (void)step; (void)noSetPrior;
    controller_step(&instance->state, instance->inputs, instance->outputs);
    return fmi2OK;
}
