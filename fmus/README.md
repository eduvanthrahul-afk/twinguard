# Testing your own software

TwinGuard can test software it didn't write, packaged as an **FMI 2.0 Co-Simulation FMU**. It treats each FMU as a black box, runs it in the same virtual car, and compares an approved version with a candidate.

Copy `.fmu` files into this folder, then open **Your software** in the dashboard. FMUs have to be copied here because there's deliberately no upload. An FMU contains native code, and testing one runs that code on this PC with your permissions, so only use files you trust.

## Signals your FMU needs

Every signal is a Real variable. Inputs have `causality="input"`, and outputs have `causality="output"`.

| Feature | Inputs | Outputs | Step |
|---|---|---|---|
| Collision braking | `sensor_new`, `target_seen`, `measured_distance`, `sensor_tick`, `tick`, `speed`, `friction` | `brake_level` | 0.01 s |
| Battery management | `cell_voltage`, `cell_temperature`, `requested_current` | `current_limit`, `contactor_closed` | 1 s |
| Adaptive cruise | `gap`, `ego_speed`, `lead_speed`, `set_speed` | `accel_command` | 0.05 s |
| Lane keeping | `lane_offset`, `heading_error`, `speed`, `curvature` | `steer_command` | 0.02 s |
| Pedestrian stop | `detected`, `distance`, `lateral`, `lateral_speed`, `speed`, `friction` | `brake_command` | 0.02 s |

For braking, `sensor_new` is 1 when a radar message is being delivered, in a step of almost no time. It's 0 for the regular control step, which sets `brake_level`.

## The examples

`src/` holds controllers written in C, compiled with Zig's C compiler. To rebuild them:

```powershell
powershell -ExecutionPolicy Bypass -File .\fmus\build.ps1
```

| File | What it is |
|---|---|
| `brake_v1_supplier.fmu` | Approved braking. It matched the Java version exactly across 3,000 scenarios |
| `brake_v2_margin_bug.fmu` | Reduced stopping margin |
| `brake_v2_radar_bug.fmu` | Radar filtering removed |
| `battery_v1_supplier.fmu` | Approved battery control |
| `battery_v2_hot_bug.fmu` | Raised temperature limits |

## Limits

- **Windows Application Control:** Smart App Control can block unsigned FMUs, and TwinGuard doesn't bypass it. It blocked the battery examples on the development PC, so they have never been run. Test your FMUs on the PC you'll present from.
- **Windows and Linux only:** an FMU has to contain a `binaries/win64` or `binaries/linux64` library. The examples are Windows only, and Linux loading is untested.
- **Black box:** a third-party FMU can't be code-mutated, because TwinGuard doesn't have its source. Mutation scores apply to TwinGuard's own logic classes.
- **Real signals only:** only Real variables are supported, and FMI 3.0 isn't.
- **Not a process boundary:** an FMU runs inside the TwinGuard process, so a crash in its native code can bring TwinGuard down. An exception in Java is caught, but a native crash isn't.
