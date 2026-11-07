# Calibration against recorded vehicle data

Two of TwinGuard's five models are now fitted to public recordings of real vehicles and real cells. The other three are still design values, and each one says so on its page.

| Feature | Data | Status |
|---|---|---|
| Adaptive cruise control | OpenACC, EU Joint Research Centre | **Calibrated** |
| Battery management | NASA PCoE Li-ion cell tests | **Calibrated** |
| Collision braking | — | Uncalibrated. CARLA re-check only |
| Lane keeping | — | Uncalibrated |
| Pedestrian emergency stop | — | Uncalibrated |

Every number below is reproducible:

```bash
bash scripts/fetch_public_data.sh                       # about 307 MB, into data/public/ (not in git)
cd backend && mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes;$(cat cp.txt)" com.twinguard.calibration.CruiseFit      # writes calibration/cruise.json
cd .. && python scripts/calibrate_battery.py                                     # writes calibration/battery.json
java -cp "backend/target/classes;$(cat backend/cp.txt)" com.twinguard.calibration.BatteryTune   # v1 BMS safety check
```

The fitted values live in `backend/src/main/resources/calibration/`. When those files are missing, each model falls back to its design values and labels itself uncalibrated.

## Adaptive cruise control

**Data.** OpenACC, AstaZero campaign, July 2019 (CC BY 4.0). Five production cars (Audi A8, Tesla Model 3, BMW X5, Audi A6, Mercedes A-Class) drove in a platoon on the AstaZero rural road, all on ACC, with differential GPS at 10 Hz. Eight recordings, 32 leader-follower pairs. Citation: Makridis et al. (2021), *Transportation Research Part C* 125.

**Method.** Each recording is cut into 30 s windows. In each window the real leader's speed is replayed, and the simulated follower, which is TwinGuard's actual `CruiseLogic` plus the cruise plant, starts from the real follower's speed and gap. A pattern search adjusts the v1 settings until the simulated gap and speed track the recording. Two whole recordings, 500 of 1,400 windows, are held out and never used for fitting.

**Result on held-out recordings:**

| | Gap error (RMS) | Speed error (RMS) |
|---|---|---|
| Design values | 16.7 m | 5.0 km/h |
| **Calibrated** | **4.4 m** | **2.3 km/h** |

By follower: Tesla Model 3 3.4 m, BMW X5 3.2 m, Mercedes A-Class 3.6 m, Audi A6 6.6 m. One model can't fit every manufacturer's tuning, and the Audi A6 fits worst.

**What changed in v1:**

| Setting | Design | Calibrated | How |
|---|---|---|---|
| Time headway | 1.8 s | 2.2 s | Fitted. The longest driver setting; the shortest fits at 0.81 s |
| Standstill gap | 6.0 m | 5.2 m | Fitted |
| Gap gain | 0.25 | 0.065 | Fitted |
| Speed gain | 0.5 | 0.5 | Fitted, unchanged |
| Gap filter | 0.3 | 0.036 | Fitted. Real ACC smooths heavily |
| Max acceleration | 1.5 m/s² | 1.31 m/s² | **Measured directly**: 99th percentile of recorded acceleration |
| Plant lag | 0.15 s | 0.6 s | Fitted, **at its physical cap** (see below) |

**Tolerances.** The gap tolerance (2.0 m) and braking tolerance (1.5 m/s²) are kept. They are far above the measured noise of the recordings (1.6 cm gap, 0.03 m/s² acceleration), so differences larger than them are real, not sensor noise.

**Limits, stated honestly:**
- The fit wanted a plant lag of 2 s. Real brakes respond in tenths of a second, so the lag is capped at 0.6 s. The extra sluggishness belongs to each car's ACC software, and our controller structure can't fully reproduce it. This is the main source of the remaining 4.4 m error.
- ACC braking in this data peaks at 4.3 m/s², and only at the back of the platoon. Hard emergency braking isn't in this data, so the emergency side of the model is still physics-based.
- One test track, dry, summer. No rain, hills, or cut-ins.
- **A real finding in the data:** braking gets harder down the platoon, peaking at 1.9, 2.6, 3.2 and 4.3 m/s² for the 2nd to 5th car. This is string instability in production ACC systems.

## Battery management

**Data.** NASA Prognostics Center of Excellence, Li-ion Battery Aging dataset (US government work). 18650 cells charged at constant current, 1.5 A to 4.2 V then constant voltage to 20 mA, and discharged at 1 to 2 A, at 24 °C and 4 °C. Citation: Saha and Goebel (2007), NASA Prognostics Data Repository.

**Method** (`scripts/calibrate_battery.py`):
- **Voltage curve and resistance:** matched charge and discharge voltages at the same state of charge give the internal resistance and the open-circuit voltage. The top of the curve comes from the constant-voltage phase, where open-circuit voltage = 4.2 V − current × resistance.
- **Cold resistance:** comparing the 4 °C cells with the 24 °C cells.
- **Heating and cooling:** least squares on the measured cell temperature.
- **Hold-out:** cells B0018, B0047 and B0048 are held out. Only each cell's first 10 cycles are used, before ageing.

**Result on held-out cells** (the model driven open-loop by the recorded current):

| | Voltage error (RMS) | Temperature error (RMS) |
|---|---|---|
| Design values | 320 mV | 2.3 °C |
| **Calibrated** | **72 mV** | **1.9 °C** |

**What changed in the cell model:** the voltage curve is now measured, where it used to be a straight line from 3.2 to 4.2 V. Resistance went from 0.06 to 0.27 V per C. Resistance now rises 4% per °C below 25 °C; it was 2%, and the measured value is 0.46 vs 0.27 at 8 °C. Heating and cooling are fitted.

**What changed in v1's BMS, and why.** On the real cell, the old v1 settings didn't work:
- It tapered at 4.15 V, which on the measured curve stops charging at about 88%. It then failed its own "charge to 90%" test.
- It allowed 3C, four times this cell's rating.
- Near freezing, the doubled resistance made v1 itself break the 4.25 V safety rule.

So v1 now uses the cell's own charging specification, as used in the NASA tests: 0.75C, then 4.2 V, finishing at 0.01C. The taper loop was re-tuned with `BatteryTune.java`, which searches for settings where v1 never breaks 4.25 V and still passes all 18 conventional tests. The worst case is 4.232 V over 3,000 random scenarios. This is a design decision based on the data, not a fitted value. Two designed faults that became identical to the new v1 were adjusted ("taper 4.2 V" and "max current cut to 2.0/1.0 C").

**Limits:**
- These are single 2008-era 18650 cells, not an EV pack. Their resistance is about twice that of modern EV cells, so this model is pessimistic for fast charging.
- No data below 4 °C. The 0 °C fast-charging rule comes from cell datasheets, not this data.
- Cooling is a lone cell in still air, slower than a liquid-cooled pack.

## Effect on the gate

Measured by running each gate with and without the calibration files:

| Release | Design values | Calibrated |
|---|---|---|
| ACC 2.1 Traffic Flow | Block (24/24 conventional pass) | Block (24/24) |
| ACC 2.2 Smoother Starts (declared) | Approve | Approve |
| ACC 2.3 Smoother Radar | Block (14/24) | Block (12/24) |
| BMS 1.1 Fast Charging | Block (18/18) | Block (18/18) |
| BMS 1.2 Longevity (declared) | Approve | Approve |
| BMS 1.3 Winter Charging | Block (18/18) | Block (18/18) |

Demo releases are now defined relative to v1, for example "ACC 2.1 follows at 40% of v1's headway", so they keep their meaning after recalibration.

## Not calibrated yet

- **Collision braking:** no public raw data on emergency braking. Published NHTSA and Euro NCAP AEB results are in PDF reports and would need hand extraction. CARLA re-check only.
- **Lane keeping:** suitable data is about 100 GB (comma2k19) or needs registration (highD).
- **Pedestrian stop:** no public raw data. Euro NCAP test speeds could be used.
- **Gateway and network timing:** public real-car CAN logs exist (HCRL) but need a manual download.
