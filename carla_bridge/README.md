# CARLA bridge

Replays TwinGuard's failing scenarios on [CARLA](https://carla.org). The sensors, gateway, and braking software keep running in Java. CARLA only simulates the vehicle and road.

```text
TwinGuard (Java)  <-- JSON over local TCP -->  bridge.py  <-- CARLA Python API -->  CARLA server
```

Tested with CARLA 0.9.16 on Windows, on an RTX 4050 with 6 GB of video memory.

## Setup on Windows

You need the CARLA 0.9.16 Windows package and Python 3.10, 3.11, or 3.12. The client doesn't support newer Python versions.

1. Download [CARLA 0.9.16 for Windows](https://downloads.carlasim.com/Windows/CARLA_0.9.16.zip) (7.3 GB) and unzip it to `C:\Users\<you>\CARLA_0.9.16`.
2. Create the Python environment, from the `carla_bridge` folder:
   ```powershell
   uv venv --python 3.12 .venv
   uv pip install --python .venv\Scripts\python.exe carla==0.9.16
   ```

## Run it

1. Start CARLA and the bridge, from the project folder:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\start-carla.ps1
   ```
2. Start TwinGuard, run a release, and click **Re-check in CARLA**. From the command line:
   ```powershell
   java -jar backend\target\twinguard.jar gate --release=v2.2-media --recheck=carla --recheck-report=recheck.json
   ```

## Calibration

CARLA's default car brakes at about 5 m/s² on any road, which doesn't match the friction the braking software is told about. `calibrate.py` measures full braking with no TwinGuard firmware involved, then sets the tyres and brake torque to match the fast simulator. It writes `calibration.json`.

To redo it, stop the bridge and run:

```powershell
.venv\Scripts\python.exe calibrate.py
```

| Road friction | Stopping-distance error against theory |
|---|---|
| 0.2 to 0.9 | Up to 12% |
| 1.0 | Up to 20%, and flagged in reports as outside calibration |

## Reading a re-check

- **Reproduced:** the same finding appeared on CARLA, and running v1 against an identical v1 didn't raise it.
- **Inconclusive:** the finding appeared, but the v1 control raised it too, so it isn't evidence against the release.
- **Not reproduced:** that finding type didn't appear. Check the other findings, because v2 may still be clearly worse. A "new collision" isn't new if v1 also clipped the obstacle.

## Testing without CARLA

`fake_carla_server.py` runs the bridge's network code with simple physics:

```powershell
cd carla_bridge
python fake_carla_server.py
```

It's a test double, and the app and reports label its results as not real CARLA. It proves that the connection works, and says nothing about how a failure behaves in CARLA.

## Known limits

- **One vehicle and map:** calibration applies to the Tesla Model 3 blueprint on `Town04_Opt`.
- **Slow:** each simulation runs tick by tick, so only the minimal failing scenarios are replayed.
- **Vehicle physics only:** radar is still simulated by TwinGuard as distance plus noise, so this doesn't test perception.
- **Failures only:** only failing scenarios are replayed, so false alarms on safe releases aren't measured on CARLA.
- **Tight scenarios:** CARLA's car stops slightly longer than theory, so v1 can clip the obstacle at low speed in scenarios with very little margin. The control exposes these cases.
- **Coasting:** the car holds its speed until the first brake request. If the brakes then release, it coasts with drag, while the fast simulator has no drag.
- **Advisory only:** a re-check is reported next to the release decision, and doesn't change it.
- **Leftover vehicles:** a force-stopped bridge leaves vehicles in the world. The bridge removes all vehicles when it starts, so give it a CARLA server of its own.
