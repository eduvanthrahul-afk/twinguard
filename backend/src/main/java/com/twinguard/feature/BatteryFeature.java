package com.twinguard.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.twinguard.calibration.Calibration;
import com.twinguard.feature.logic.BatteryLogic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Battery management during a charging session. The cell model covers open-circuit voltage,
 * internal resistance, self-heating, cooling, and one weak cell in the pack.
 */
public final class BatteryFeature implements VehicleFeature {

    static final double MAX_SAFE_VOLTAGE = 4.25;
    static final double MAX_SAFE_TEMPERATURE = 60.0;
    static final double COLD_LIMIT_C = 0.5;
    private static final double DT_S = 1.0;
    private static final int MAX_STEPS = 7200;
    private static final int TRACE_EVERY = 20;
    /** Fitted to NASA PCoE cell tests when calibration/battery.json is present. */
    private static final Optional<JsonNode> CAL = Calibration.load("battery");
    private static final double RESISTANCE = Calibration.value(CAL, "cell", "resistance", 0.06);
    private static final double COLD_RESISTANCE = Calibration.value(CAL, "cell", "coldResistancePerDegree", 1.0 / 50);
    private static final double HEATING = Calibration.value(CAL, "cell", "heating", 0.004);
    private static final double COOLING = Calibration.value(CAL, "cell", "cooling", 0.002);
    private static final double[] OCV_SOC = table("soc", new double[] {0, 1});
    private static final double[] OCV_VOLTS = table("volts", new double[] {3.2, 4.2});
    private static final double TEMPERATURE_NOISE = 0.3;
    private static final double VOLTAGE_TOLERANCE = Calibration.value(CAL, "tolerances", "voltage", 0.02);
    private static final double TEMPERATURE_TOLERANCE = Calibration.value(CAL, "tolerances", "temperature", 2.0);
    private static final double CHARGE_TOLERANCE = 0.05;

    @Override
    public String id() {
        return "battery";
    }

    @Override
    public String name() {
        return "Battery management";
    }

    @Override
    public String summary() {
        return "Controls charging current, and cuts off on overvoltage or overheating.";
    }

    @Override
    public String limitations() {
        return "A simple single-cell thermal and voltage model with one weak cell. It doesn't cover ageing, lithium plating chemistry, thermal runaway, or real pack hardware. "
                + Calibration.summary(CAL);
    }

    private static double[] table(String key, double[] fallback) {
        return CAL.map(c -> c.path("ocv").path(key)).filter(JsonNode::isArray)
                .map(a -> { double[] v = new double[a.size()]; for (int i = 0; i < v.length; i++) { v[i] = a.get(i).asDouble(); } return v; })
                .orElse(fallback);
    }

    /** Open-circuit voltage at a state of charge, interpolated from the table and extended linearly past its ends. */
    static double ocv(double soc) {
        int n = OCV_SOC.length;
        int i = 0;
        while (i < n - 2 && soc > OCV_SOC[i + 1]) {
            i++;
        }
        double f = (soc - OCV_SOC[i]) / (OCV_SOC[i + 1] - OCV_SOC[i]);
        return OCV_VOLTS[i] + f * (OCV_VOLTS[i + 1] - OCV_VOLTS[i]);
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param("chargeRate", "Requested charge rate", "C", 0.2, 3.0, 1.0),
                new Param("ambient", "Ambient temperature", "°C", -10, 50, 25),
                new Param("startSoc", "Starting charge", "", 0.1, 0.95, 0.5),
                new Param("imbalance", "Weak-cell imbalance", "", 0, 0.08, 0),
                new Param("coolingLoss", "Cooling loss", "", 0, 1, 0),
                new Param("voltageNoise", "Voltage sensor noise", "V", 0, 0.01, 0));
    }

    @Override
    public int coverageX() {
        return 0;
    }

    @Override
    public int coverageY() {
        return 1;
    }

    @Override
    public List<Channel> channels() {
        return List.of(
                new Channel("voltage", "Weak-cell voltage", "V"),
                new Channel("temperature", "Cell temperature", "°C"),
                new Channel("current", "Charge current", "C"),
                new Channel("soc", "Weak-cell charge", ""));
    }

    @Override
    public double stepSeconds() {
        return DT_S;
    }

    @Override
    public List<String> inputNames() {
        return List.of("cell_voltage", "cell_temperature", "requested_current");
    }

    @Override
    public List<String> outputNames() {
        return List.of("current_limit", "contactor_closed");
    }

    @Override
    public Map<String, Double> defaultSettings() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("taperVoltage", Calibration.value(CAL, "settings", "taperVoltage", 4.15));
        s.put("cutoffVoltage", Calibration.value(CAL, "settings", "cutoffVoltage", 4.22));
        s.put("taperGain", Calibration.value(CAL, "settings", "taperGain", 0.2));
        s.put("derateTemperature", 45.0);
        s.put("cutoffTemperature", 55.0);
        s.put("coldTemperature", 2.0);
        s.put("coldCurrentLimit", 0.3);
        s.put("fullCurrent", Calibration.value(CAL, "settings", "fullCurrent", 0.05));
        s.put("maxCurrent", Calibration.value(CAL, "settings", "maxCurrent", 3.0));
        s.put("confirmSamples", 3.0);
        s.put("ramp", Calibration.value(CAL, "settings", "ramp", 0.05));
        return s;
    }

    @Override
    public FeatureController logic(Map<String, Double> settings) {
        return new BatteryLogic(settings);
    }

    @Override
    public Class<? extends FeatureController> logicClass() {
        return BatteryLogic.class;
    }

    @Override
    public List<SettingFault> faults() {
        List<SettingFault> f = new ArrayList<>();
        for (double v : new double[] {4.25, 4.3}) {
            f.add(new SettingFault("taper-" + v, "Voltage limits", "Taper voltage set to " + v + " V", Map.of("taperVoltage", v)));
        }
        for (double v : new double[] {4.25, 4.35, 4.5}) {
            f.add(new SettingFault("cutoff-v-" + v, "Voltage limits", "Voltage cut-off raised to " + v + " V", Map.of("cutoffVoltage", v)));
        }
        for (double v : new double[] {0.02, 0.05}) {
            f.add(new SettingFault("taper-gain-" + v, "Voltage limits", "Taper response slowed to " + v, Map.of("taperGain", v)));
        }
        for (double v : new double[] {50.0, 55.0}) {
            f.add(new SettingFault("derate-" + v, "Temperature limits", "Derating starts at " + v + " °C", Map.of("derateTemperature", v)));
        }
        for (double v : new double[] {58.0, 65.0, 80.0}) {
            f.add(new SettingFault("cutoff-t-" + v, "Temperature limits", "Temperature cut-off raised to " + v + " °C", Map.of("cutoffTemperature", v, "derateTemperature", Math.max(45.0, v - 10))));
        }
        for (double v : new double[] {35.0, 40.0}) {
            f.add(new SettingFault("cutoff-low-" + v, "Temperature limits", "Temperature cut-off lowered to " + v + " °C", Map.of("cutoffTemperature", v, "derateTemperature", v - 10)));
        }
        for (double v : new double[] {0.6, 1.0, 3.0}) {
            f.add(new SettingFault("cold-limit-" + v, "Cold charging", "Cold charge limit raised to " + v + " C", Map.of("coldCurrentLimit", v)));
        }
        f.add(new SettingFault("cold-disabled", "Cold charging", "Cold protection only below -20 °C", Map.of("coldTemperature", -20.0)));
        double maxCurrent = defaultSettings().get("maxCurrent");
        for (double v : new double[] {2.0, 1.5}) {
            f.add(new SettingFault("max-current-" + v, "Current limits", "Maximum current raised to " + v + " C", Map.of("maxCurrent", v)));
        }
        f.add(new SettingFault("max-current-low", "Current limits", "Maximum current cut to " + Math.round(maxCurrent * 50) / 100.0 + " C", Map.of("maxCurrent", maxCurrent / 2)));
        for (double v : new double[] {0.3, 0.6}) {
            f.add(new SettingFault("full-current-" + v, "Current limits", "Charge ends early, below " + v + " C", Map.of("fullCurrent", v)));
        }
        for (double v : new double[] {0.5, 3.0}) {
            f.add(new SettingFault("ramp-" + v, "Current limits", "Current ramp raised to " + v + " C per second", Map.of("ramp", v)));
        }
        for (double v : new double[] {1.0, 30.0, 300.0}) {
            f.add(new SettingFault("confirm-" + v, "Fault handling", "Overvoltage needs " + (int) v + " samples to confirm", Map.of("confirmSamples", v)));
        }
        return f;
    }

    @Override
    public Map<String, String> declarableChanges() {
        return Map.of("LOWER_CHARGE", "Less charge may be delivered than v1");
    }

    @Override
    public List<Release> releases() {
        return List.of(
                new Release("bms-1.1-fast-charge", "BMS 1.1 Fast Charging", "Raises temperature limits so the car charges faster in hot weather.",
                        Map.of("derateTemperature", 52.0, "cutoffTemperature", 64.0), Set.of(), "No behaviour change declared"),
                new Release("bms-1.2-longevity", "BMS 1.2 Battery Longevity", "Tapers slightly earlier to reduce cell wear. This change is declared.",
                        Map.of("taperVoltage", Math.round((defaultSettings().get("taperVoltage") - 0.01) * 1000) / 1000.0), Set.of("LOWER_CHARGE"), "Slightly less charge may be delivered"),
                new Release("bms-1.3-winter", "BMS 1.3 Winter Charging", "Allows faster charging in the cold, for ski-resort charging stops.",
                        Map.of("coldCurrentLimit", 1.2), Set.of(), "No behaviour change declared"));
    }

    @Override
    public Result simulate(FeatureController controller, double[] s, long seed, boolean withTrace) {
        double requested = s[0];
        double ambient = s[1];
        double soc = s[2];
        double imbalance = s[3];
        double cooling = COOLING * (1 - 0.85 * s[4]);
        double noise = s[5];
        Random rng = new Random(seed);

        double temperature = ambient;
        double current = 0;
        double peakVoltage = 0;
        double peakTemperature = temperature;
        double peakColdCurrent = 0;
        double delivered = 0;
        double opened = 0;
        double cutoffAt = -1;
        List<double[]> trace = withTrace ? new ArrayList<>() : List.of();

        for (int step = 0; step < MAX_STEPS; step++) {
            double weakCell = soc + imbalance;
            double voltage = ocv(weakCell) + current * RESISTANCE * (1 + COLD_RESISTANCE * Math.max(0, 25 - temperature));
            double[] out = controller.step(new double[] {
                    voltage + rng.nextGaussian() * noise,
                    temperature + rng.nextGaussian() * TEMPERATURE_NOISE,
                    requested});
            current = out[1] > 0.5 ? Math.max(0, Math.min(out[0], requested)) : 0;

            soc = Math.min(1.2, soc + current * DT_S / 3600);
            delivered += current * DT_S / 3600;
            temperature += (current * current * HEATING - (temperature - ambient) * cooling) * DT_S;

            peakVoltage = Math.max(peakVoltage, voltage);
            peakTemperature = Math.max(peakTemperature, temperature);
            if (temperature < 0) {
                peakColdCurrent = Math.max(peakColdCurrent, current);
            }
            if (withTrace && step % TRACE_EVERY == 0) {
                trace.add(new double[] {step * DT_S, voltage, temperature, current, soc + imbalance});
            }
            if (out[1] <= 0.5) {
                opened = 1;
                cutoffAt = step * DT_S;
                break;
            }
        }

        Map<String, Double> m = new LinkedHashMap<>();
        m.put("peakVoltage", peakVoltage);
        m.put("peakTemperature", peakTemperature);
        m.put("peakColdCurrent", peakColdCurrent);
        m.put("finalCharge", soc + imbalance);
        m.put("delivered", delivered);
        m.put("cutoff", opened);
        m.put("cutoffAt", cutoffAt);
        return new Result(m, trace);
    }

    @Override
    public List<FeatureFinding> judge(double[] s, Result v1, Result v2, Set<String> declared) {
        List<FeatureFinding> out = new ArrayList<>();
        Map<String, Double> a = v1.metrics();
        Map<String, Double> b = v2.metrics();
        if (b.get("peakVoltage") > MAX_SAFE_VOLTAGE) {
            out.add(FeatureFinding.critical("OVERVOLTAGE", "Overvoltage", String.format("A cell reached %.3f V, above the %.2f V limit", b.get("peakVoltage"), MAX_SAFE_VOLTAGE)));
        }
        if (b.get("peakTemperature") > MAX_SAFE_TEMPERATURE) {
            out.add(FeatureFinding.critical("OVERHEATING", "Overheating", String.format("Cells reached %.1f °C, above the %.0f °C limit", b.get("peakTemperature"), MAX_SAFE_TEMPERATURE)));
        }
        if (b.get("peakColdCurrent") > COLD_LIMIT_C) {
            out.add(FeatureFinding.critical("COLD_CHARGING", "Fast charging while frozen", String.format("Charged at %.2f C below 0 °C, above the %.1f C limit", b.get("peakColdCurrent"), COLD_LIMIT_C)));
        }
        // A cell can start above full in a scenario, so this only counts charge that was actually added.
        if (b.get("finalCharge") > 1.0 && b.get("delivered") > 0.005) {
            out.add(FeatureFinding.critical("OVERCHARGE", "Overcharge", String.format("The weak cell reached %.1f%% charge", b.get("finalCharge") * 100)));
        }
        if (b.get("peakTemperature") > a.get("peakTemperature") + TEMPERATURE_TOLERANCE) {
            out.add(FeatureFinding.high("HOTTER", "Runs hotter than v1", String.format("Peak temperature rose from %.1f to %.1f °C", a.get("peakTemperature"), b.get("peakTemperature"))));
        }
        if (b.get("peakVoltage") > a.get("peakVoltage") + VOLTAGE_TOLERANCE) {
            out.add(FeatureFinding.high("HIGHER_VOLTAGE", "Higher voltage than v1", String.format("Peak voltage rose from %.3f to %.3f V", a.get("peakVoltage"), b.get("peakVoltage"))));
        }
        if (b.get("delivered") < a.get("delivered") * (1 - CHARGE_TOLERANCE) && !declared.contains("LOWER_CHARGE")) {
            out.add(FeatureFinding.medium("LOWER_CHARGE", "Less charge delivered", String.format("Delivered %.1f%% of capacity, while v1 delivered %.1f%%", b.get("delivered") * 100, a.get("delivered") * 100)));
        }
        return out;
    }

    @Override
    public double[] nearBoundary(Random rng) {
        double[] s = uniform(rng);
        switch (rng.nextInt(3)) {
            case 0 -> { // Heat: a hot day, or weak cooling.
                s[0] = 1.5 + rng.nextDouble() * 1.5;
                s[1] = 30 + rng.nextDouble() * 20;
            }
            case 1 -> { // Cold charging.
                s[1] = -10 + rng.nextDouble() * 12;
                s[0] = 0.2 + rng.nextDouble() * 2;
            }
            default -> { // Nearly full, where voltage peaks.
                s[2] = 0.75 + rng.nextDouble() * 0.2;
                s[3] = rng.nextDouble() * 0.08;
            }
        }
        return s;
    }

    @Override
    public List<double[]> conventionalScenarios() {
        return VehicleFeature.grid(new double[][] {{0.5, 1.0}, {15, 25, 30}, {0.3, 0.5, 0.7}, {0}, {0}, {0.002}});
    }

    @Override
    public boolean conventionalPass(double[] s, Result r) {
        return r.metrics().get("peakVoltage") <= MAX_SAFE_VOLTAGE
                && r.metrics().get("peakTemperature") <= MAX_SAFE_TEMPERATURE
                && r.metrics().get("finalCharge") >= 0.9;
    }
}
