"""Fits TwinGuard's battery model to the NASA PCoE Li-ion Battery Aging dataset.

Data: B. Saha and K. Goebel (2007), "Battery Data Set", NASA Prognostics Data Repository,
NASA Ames Research Center. US government work. Download with scripts/fetch_public_data.sh.

The model in BatteryFeature.java, with current I in C (1 C charges the cell in an hour, positive when charging):
    V     = OCV(soc) + I * R * (1 + k * max(0, 25 - T))
    dT/dt = H * I^2 - K * (T - T_ambient)

Fitted here:
    OCV(soc)  open-circuit voltage table, from matched charge and discharge curves
    R         internal resistance at 25 C, in volts per C
    k         how much faster resistance rises per degree below 25 C, from the 4 C cells
    H, K      heating and cooling, by least squares on the measured cell temperature

Cells B0005, B0006, B0007 (24 C) and B0045, B0046 (4 C) are used for fitting.
Cells B0018 (24 C) and B0047, B0048 (4 C) are held out and only used to report the error.
Only the first cycles of each cell are used, before ageing changes its behaviour.

Usage: python -I scripts/calibrate_battery.py <dataset folder> <output json>
"""
import json
import sys
from pathlib import Path

import numpy as np
import scipy.io as sio

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "data/public/nasa_battery/raw/5. Battery Data Set")
OUT = Path(sys.argv[2] if len(sys.argv) > 2 else "backend/src/main/resources/calibration/battery.json")
EARLY_CYCLES = 10
SOC_GRID = np.round(np.arange(0.0, 1.0001, 0.05), 2)

TRAIN = {"B0005": 24, "B0006": 24, "B0007": 24, "B0045": 4, "B0046": 4}
TEST = {"B0018": 24, "B0047": 4, "B0048": 4}

DESIGN = {"ocv": [(0.0, 3.2), (1.0, 4.2)], "R": 0.06, "k": 1 / 50, "H": 0.004, "K": 0.002}


def find(cell):
    return next(ROOT.rglob(f"{cell}.mat"))


def cycles(cell):
    """Early charge and discharge cycles, each paired with the capacity of the nearest discharge."""
    raw = sio.loadmat(find(cell), simplify_cells=True)[cell]["cycle"]
    ops = [c for c in raw if c["type"] in ("charge", "discharge")]
    caps = [c["data"]["Capacity"] for c in ops if c["type"] == "discharge" and np.isscalar(c["data"].get("Capacity"))]
    out = []
    discharges = 0
    for i, c in enumerate(ops):
        d = c["data"]
        t = np.asarray(d["Time"], float)
        if t.size < 20:
            continue
        cap = None
        # The capacity of the discharge closest to this operation.
        for j in list(range(i, len(ops))) + list(range(i - 1, -1, -1)):
            cd = ops[j]["data"]
            if ops[j]["type"] == "discharge" and np.isscalar(cd.get("Capacity")) and cd["Capacity"] > 0.5:
                cap = float(cd["Capacity"])
                break
        if cap is None:
            continue
        out.append({
            "type": c["type"], "amb": float(c["ambient_temperature"]), "cap": cap,
            "t": t, "I": np.asarray(d["Current_measured"], float),
            "V": np.asarray(d["Voltage_measured"], float), "T": np.asarray(d["Temperature_measured"], float),
        })
        if c["type"] == "discharge":
            discharges += 1
            if discharges >= EARLY_CYCLES:
                break
    return out


def ah(t, I):
    """Charge passed, in amp-hours, by trapezoidal integration."""
    return np.concatenate([[0], np.cumsum((I[1:] + I[:-1]) / 2 * np.diff(t))]) / 3600


def soc_curve(c):
    """State of charge along a cycle. Discharge starts full; a charge ends full."""
    q = ah(c["t"], c["I"])
    if c["type"] == "discharge":
        return 1 + q / c["cap"]
    return 1 - (q[-1] - q) / c["cap"]


def constant_current(c, level):
    return np.abs(c["I"] - level) < 0.05 * abs(level) + 0.02


def ocv_and_resistance(cells):
    """Matches charge and discharge voltages at the same state of charge.
    Charging pushes the voltage up by I*R and discharging pulls it down, so the two give R and OCV."""
    rs, ocvs = [], []
    for cell in cells:
        cs = cycles(cell)
        for a, b in zip(cs, cs[1:]):
            pair = {a["type"]: a, b["type"]: b}
            if set(pair) != {"charge", "discharge"}:
                continue
            ch, dis = pair["charge"], pair["discharge"]
            ic = np.median(ch["I"][ch["I"] > 0.5])
            idis = np.median(dis["I"][dis["I"] < -0.5])
            mc = constant_current(ch, ic)
            md = constant_current(dis, idis)
            sc, sd = soc_curve(ch)[mc], soc_curve(dis)[md]
            vc, vd = ch["V"][mc], dis["V"][md]
            if sc.size < 10 or sd.size < 10:
                continue
            oc, od = np.argsort(sc), np.argsort(sd)
            grid = SOC_GRID[(SOC_GRID >= max(sc.min(), sd.min())) & (SOC_GRID <= min(sc.max(), sd.max()))]
            if grid.size < 3:
                continue
            Vc = np.interp(grid, sc[oc], vc[oc])
            Vd = np.interp(grid, sd[od], vd[od])
            cap = (ch["cap"] + dis["cap"]) / 2
            # In C: |I| / capacity.
            Ic, Id = ic / cap, -idis / cap
            R = (Vc - Vd) / (Ic + Id)
            r = np.median(R[(grid >= 0.2) & (grid <= 0.8)])
            rs.append(r)
            points = dict(zip(grid, Vd + Id * R))
            # Above the constant-current range, the charger holds the cell near 4.2 V while the current tapers,
            # so OCV = V - I*R gives the top of the curve. As the current falls to 20 mA, this approaches true OCV.
            cv = (ch["V"] > 4.19) & (ch["I"] > 0.015) & (ch["I"] < 0.9 * ic)
            if cv.sum() > 5:
                scv = soc_curve(ch)[cv]
                ocv_cv = ch["V"][cv] - ch["I"][cv] / cap * r
                o = np.argsort(scv)
                top = SOC_GRID[(SOC_GRID > grid.max()) & (SOC_GRID >= scv.min()) & (SOC_GRID <= scv.max())]
                points.update(zip(top, np.interp(top, scv[o], ocv_cv[o])))
                points[1.0] = float(ocv_cv[np.argmax(scv)])
            ocvs.append(points)
    table = {}
    for s in SOC_GRID:
        vals = [o[s] for o in ocvs if s in o]
        if vals:
            table[float(s)] = float(np.median(vals))
    return float(np.median(rs)), table


def mean_cell_temperature(cells):
    temps = []
    for cell in cells:
        for c in cycles(cell):
            temps.append(np.mean(c["T"]))
    return float(np.mean(temps))


def thermal(cells):
    """Least squares on dT/dt = H*I^2 - K*(T - T_ambient), with I in C."""
    rows, target = [], []
    for cell in cells:
        for c in cycles(cell):
            t, T = c["t"], c["T"]
            Ic = c["I"] / c["cap"]
            dt = np.diff(t)
            ok = (dt > 0) & (dt < 60)
            dTdt = np.diff(T) / np.where(ok, dt, 1)
            mid = (Ic[1:] + Ic[:-1]) / 2
            Tm = (T[1:] + T[:-1]) / 2
            rows.append(np.column_stack([mid[ok] ** 2, -(Tm[ok] - c["amb"])]))
            target.append(dTdt[ok])
    A = np.vstack(rows)
    y = np.concatenate(target)
    (H, K), *_ = np.linalg.lstsq(A, y, rcond=None)
    return float(H), float(K)


def ocv_at(table, soc):
    xs = np.array(sorted(table))
    ys = np.array([table[x] for x in xs])
    return np.interp(soc, xs, ys, left=ys[0] + (soc - xs[0]) * (ys[1] - ys[0]) / (xs[1] - xs[0]),
                     right=ys[-1] + (soc - xs[-1]) * (ys[-1] - ys[-2]) / (xs[-1] - xs[-2]))


def predict(c, m):
    """Runs the model open loop on a recorded cycle: measured current in, voltage and temperature out."""
    soc = soc_curve(c)
    Ic = c["I"] / c["cap"]
    T = np.empty_like(c["T"])
    T[0] = c["T"][0]
    for i in range(1, len(T)):
        dt = c["t"][i] - c["t"][i - 1]
        T[i] = T[i - 1] + (m["H"] * Ic[i - 1] ** 2 - m["K"] * (T[i - 1] - c["amb"])) * dt
    ocv = np.array([ocv_at(m["ocv"], s) for s in soc])
    V = ocv + Ic * m["R"] * (1 + m["k"] * np.maximum(0, 25 - T))
    return V, T


def errors(cells, m):
    """RMS voltage error over the constant-current part, and RMS temperature error over the whole cycle."""
    dv, dT = [], []
    for cell in cells:
        for c in cycles(cell):
            V, T = predict(c, m)
            level = np.median(c["I"][np.abs(c["I"]) > 0.5]) if np.any(np.abs(c["I"]) > 0.5) else 0
            cc = constant_current(c, level) & (c["V"] > 2.9)
            dv.append((V - c["V"])[cc])
            dT.append(T - c["T"])
    dv, dT = np.concatenate(dv), np.concatenate(dT)
    return {"voltageRmsV": round(float(np.sqrt(np.mean(dv ** 2))), 3),
            "temperatureRmsC": round(float(np.sqrt(np.mean(dT ** 2))), 2)}


def noise(cells):
    """Measurement scatter around a short moving average, during constant-current charging."""
    sv, sT = [], []
    for cell in cells:
        for c in cycles(cell):
            if c["type"] != "charge":
                continue
            m = constant_current(c, 1.5)
            for x, out in ((c["V"][m], sv), (c["T"][m], sT)):
                if x.size > 9:
                    avg = np.convolve(x, np.ones(5) / 5, mode="valid")
                    out.append(x[2:-2] - avg)
    return float(np.std(np.concatenate(sv))), float(np.std(np.concatenate(sT)))


def main():
    warm = [c for c, a in TRAIN.items() if a == 24]
    cold = [c for c, a in TRAIN.items() if a == 4]
    R25, table = ocv_and_resistance(warm)
    Rcold, _ = ocv_and_resistance(cold)
    Tcold = mean_cell_temperature(cold)
    k = max(0.0, (Rcold / R25 - 1) / max(1.0, 25 - Tcold))
    H, K = thermal(list(TRAIN))
    fitted = {"ocv": table, "R": R25, "k": k, "H": H, "K": K}
    design = {"ocv": dict(DESIGN["ocv"]), "R": DESIGN["R"], "k": DESIGN["k"], "H": DESIGN["H"], "K": DESIGN["K"]}

    before, after = errors(list(TEST), design), errors(list(TEST), fitted)
    sigma_v, sigma_t = noise(list(TRAIN))
    summary = (
        "Calibrated against NASA PCoE Li-ion cell tests (18650 cells at 4 C and 24 C). "
        f"On held-out cells the voltage is within {after['voltageRmsV'] * 1000:.0f} mV RMS and temperature within "
        f"{after['temperatureRmsC']:.1f} C RMS (design values: {before['voltageRmsV'] * 1000:.0f} mV, {before['temperatureRmsC']:.1f} C). "
        "Single cells, not a pack, and no data below 4 C."
    )
    out = {
        "ocv": {"soc": list(table.keys()), "volts": [round(v, 4) for v in table.values()]},
        "cell": {"resistance": round(R25, 4), "coldResistancePerDegree": round(k, 5),
                 "heating": round(H, 6), "cooling": round(K, 6)},
        # v1's charge profile follows the cell's own charging specification, as used in the NASA tests:
        # constant current at 1.5 A (0.75 C for a 2 Ah cell) to 4.2 V, then constant voltage until 20 mA (0.01 C).
        # The over-voltage cut-off sits between the 4.2 V charge voltage and the 4.25 V safety rule.
        "settings": {"maxCurrent": 0.75, "taperVoltage": 4.2, "fullCurrent": 0.01, "cutoffVoltage": 4.23,
                     # Re-tuned with BatteryTune.java so v1 stays under 4.25 V on the calibrated cell, whose
                     # resistance roughly doubles near freezing: worst case 4.232 V over 3,000 random scenarios.
                     "taperGain": 2.0, "ramp": 0.005,
                     "basis": "NASA test charging profile: CC 1.5 A (0.75 C) to 4.2 V, then CV until 20 mA (0.01 C). Taper loop re-tuned for the measured cold resistance."},
        "tolerances": {"voltage": round(max(0.02, 3 * sigma_v), 3), "temperature": round(max(2.0, 3 * sigma_t), 2)},
        "fit": {"heldOutCells": list(TEST), "trainCells": list(TRAIN), "cyclesPerCell": EARLY_CYCLES,
                "heldOutBefore": before, "heldOutAfter": after,
                "resistance25C": round(R25, 4), "resistanceCold": round(Rcold, 4), "coldCellTemperature": round(Tcold, 1)},
        "observed": {"voltageNoiseV": round(sigma_v, 4), "temperatureNoiseC": round(sigma_t, 3)},
        "source": {"dataset": "NASA PCoE Li-ion Battery Aging Datasets (Battery Data Set)",
                   "publisher": "NASA Ames Research Center, Prognostics Center of Excellence",
                   "licence": "US government work",
                   "url": "https://phm-datasets.s3.amazonaws.com/NASA/5.+Battery+Data+Set.zip",
                   "citation": "B. Saha and K. Goebel (2007). Battery Data Set, NASA Prognostics Data Repository."},
        "summary": summary,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, indent=2))
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
