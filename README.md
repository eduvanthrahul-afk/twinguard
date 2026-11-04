# TwinGuard

Differential shadow testing with mutation-proven confidence for OTA releases.

TwinGuard runs an approved firmware (v1) and an OTA candidate (v2) through identical simulated road conditions and sensor noise, then blocks unsafe releases. It also measures how far a passing result can be trusted, by injecting known faults and counting how many the process catches.

For example, a media player update changes no braking code and passes all 48 conventional tests. TwinGuard still blocks it, because under heavy media load its traffic delays the radar and brake messages, and the car hits an obstacle that v1 stops for.

> **These are real computed results from a simulated car, not real vehicle data.** Scenarios and sensor data are synthetic, and the models are simplified and uncalibrated against real vehicles. An approval is evidence, not proof of safety.

## Measured results

From the last full run, and measured live each time the app starts:

| Feature | Code mutants caught | Random, same budget | Designed faults caught | False alarms |
|---|---|---|---|---|
| Collision braking | 56 of 100 (56%) | 57 of 100 | 63 of 72 | 0 in 40,000 scenarios |
| Battery management | 33 of 50 (66%) | 34 of 50 | 24 of 28 | 0 in 16,000 |
| Adaptive cruise control | 44 of 64 (69%) | 45 of 64 | 23 of 26 | 0 in 16,000 |
| Lane keeping | 20 of 30 (67%) | 20 of 30 | 21 of 25 | 0 in 16,000 |
| Pedestrian emergency stop | 50 of 70 (71%) | 50 of 70 | 26 of 28 | 0 in 16,000 |
| **Total** | **203 of 314 (65%)** | **206 of 314** | **157 of 179 (88%)** | **0 in 104,000** |

- **Code mutants** are real bugs a tool creates by rewriting the compiled logic. Nothing was tuned against them, so they're the harder and more independent test. **Designed faults** were chosen by us.
- **Search doesn't beat random testing** at this budget. Change-aware testing finds braking faults sooner, but not more of them.
- **CARLA:** 12 braking failures from three buggy releases were replayed once each on calibrated CARLA 0.9.16. 9 reproduced, 1 was inconclusive, and 2 showed a different finding. This used CARLA's vehicle physics with TwinGuard's simulated radar.
- **FMU:** an approved braking controller written in C behaved the same as the Java version across 3,000 scenarios, with zero difference in brake timing and stopping gap.

## What it covers

Five vehicle features, each with its own scenarios, physics, software, and safety rules:

| Feature | Fixed safety rules |
|---|---|
| **Collision braking** | No avoidable collision, no phantom braking. Includes the infotainment and gateway network, a replay, and a CARLA re-check |
| **Battery management** | Cell voltage at most 4.25 V, temperature at most 60 °C, no fast charging below 0 °C, no overcharging |
| **Adaptive cruise control** | No avoidable collision, no exceeding the set speed |
| **Lane keeping** | Stay in lane where the tyres can hold the curve, no steering with nothing to correct |
| **Pedestrian emergency stop** | No avoidable hit, no false stops. Detection is simulated, with no camera or real perception |

It can also test **software it didn't write**, packaged as an FMI 2.0 FMU. See `fmus/README.md`.

To add a feature, implement `VehicleFeature`, then list it in `Features.java`. The engine supplies the search, coverage, confirmation, shrinking, scoring, and API.

Collision braking predates the plugin system and still runs on its own engine, which is why it has extras like the replay and CARLA re-check. The four plugin features have simplified models, and they haven't been run on CARLA.

## Run it

Requires Java 17+, Maven, and Node.

```powershell
powershell -ExecutionPolicy Bypass -File .\start.ps1 -Rebuild
```

Open http://127.0.0.1:8787. The scores are measured at startup, which takes about a minute, and the app is usable while that runs.

Optional extras:

- **CARLA re-check:** see `carla_bridge/README.md`.
- **Your own FMUs:** see `fmus/README.md`. Rebuilding the example FMUs needs [Zig](https://ziglang.org).

For frontend development, run the backend, then `npm run dev` in `frontend/` and open http://localhost:5173.

## How it works

This describes collision braking. The plugin features follow the same process, with their own scenarios and rules.

| Brief asks | TwinGuard's answer |
|---|---|
| **Test environment** | A mocked vehicle: sensor ECU, gateway with priority queues, braking ECU, brake actuator, infotainment traffic, and vehicle physics |
| **Test generation** | Varies speed, distance, friction, sensor noise, and infotainment load. Includes false radar returns |
| **Prioritisation** | Favours near-collision scenarios, and scenarios linked to the settings v2 changed |
| **Coverage** | A 1,088-cell map across speed, distance, friction, and load, plus false-return scenarios |
| **Detection** | Compares v2 with v1, and also applies a fixed rule against avoidable collisions. Findings must repeat on 2 of 3 noise seeds |
| **Confidence** | 72 generated faults, a held-out subset, Wilson 95% ranges, comparison with random testing, and known-safe controls to measure false alarms. Also about 100 real code mutants, created by rewriting the compiled braking code, and never used for tuning |
| **Higher fidelity** | Failing scenarios can be replayed on CARLA through a pluggable simulator interface. See `carla_bridge/README.md` |
| **Debugging** | Shrinks each failure to its simplest form, with a side-by-side replay |
| **Release integration** | A command-line gate with exit codes, a JSON and HTML evidence report, and a GitHub Actions workflow |

The change manifest lets a release declare intended differences. Declared differences are not reported as regressions, but the fixed safety rule and the minimum stopping gap can't be waived.

## Command line

```powershell
java -jar backend\target\twinguard.jar gate --release=v2.2-media --report=gate-report.json --html=gate-report.html
java -jar backend\target\twinguard.jar campaign --report=campaign.json --min-score=70
java -jar backend\target\twinguard.jar mutation --report=mutation.json
java -jar backend\target\twinguard.jar gate --release=v2.2-media --recheck=carla --recheck-report=recheck.json
java -jar backend\target\twinguard.jar feature --id=battery --release=bms-1.3-winter --report=battery.json
java -jar backend\target\twinguard.jar gate --fmu-baseline=fmus\brake_v1_supplier.fmu --fmu-candidate=fmus\brake_v2_radar_bug.fmu
```

Gate exit codes: `0` approve, `1` block, `2` needs review, `3` bad usage. The campaign exits `1` if the score is below `--min-score`.

Releases: `v2.1-comfort` (safe), `v2.2-media`, `v2.3-nuisance`, `v2.4-fast-radar`.

## Reading the results

The numbers come from a live campaign, so read them from the **Confidence** page or the campaign report. Results are computed by the simulator. Road scenarios and sensor data are synthetic, and firmware settings are design values, not taken from a real car.

- The main score includes every fault. Quote the score that excludes no-visible-change faults only alongside it.
- Read the generated fault score next to the code mutant score. The mutants are harder, and nobody chose them. A mutant that never changed braking isn't proven harmless, because it may need conditions we don't test.
- A CARLA re-check is reported separately, and doesn't change the release decision. CARLA's braking is calibrated to within 12% of theory for road friction 0.2 to 0.9, and each scenario also runs a v1 against v1 control. Radar is still simulated, so it doesn't test perception.
- Change-aware testing finds faults sooner than random testing, not more of them. Coverage-guided search alone does not beat random in this simulator.
- Scores apply to this fault set and this simulator, not to all real-world bugs.
- Held-out faults never feed the test corpus, but the generator was revised while their results were visible, so this is not a formally blind test.

## Limitations

TwinGuard does not catch or model:

- Camera or radar perception faults. Sensors are simulated as values plus noise, including pedestrian detection
- Real ECU timing, CAN bus electrical faults, or hardware failures
- Moving obstacles in the braking feature, cut-ins, or real battery chemistry
- Combined faults. Each fault is injected alone, so some can be masked
- Changes inside the tolerances, such as a 5% threshold shift or a 10% force reduction
- Every model here is simplified, and uncalibrated against real vehicle data

Each feature's own limits are listed on its page.

An APPROVE verdict is evidence, not proof of safety, and is not a certification against any standard.

## Project layout

```text
backend/src/main/java/com/twinguard/
  feature/    The plugin system, and the battery, cruise, lane, and pedestrian features
  fmu/        Loads and runs third-party FMI 2.0 FMUs
  sim/        Vehicle, sensor, gateway, and braking simulation, with a pluggable vehicle plant
  mutation/   Rewrites compiled logic into real bugs, and scores the gate on them
  carla/      Connects the simulator interface to CARLA through the bridge
  oracle/     Safety checks and repeat-seed confirmation
  catalog/    Generated faults, demo releases, Fault Lab settings
  testgen/    Scenario search, coverage map, shrinking, conventional suite
  gate/       Release decision and evidence report
  campaign/   Fault injection measurement
  api/        REST and live streaming endpoints
  cli/        CI command line and HTML evidence report
frontend/src/ React dashboard
carla_bridge/ Python bridge to a CARLA server, and a clearly labelled test double
fmus/         Your FMU files, plus example controllers written in C
.github/      CI workflow
DEMO.md       Presentation script
```
