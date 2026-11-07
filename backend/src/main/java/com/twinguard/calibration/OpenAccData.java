package com.twinguard.calibration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads OpenACC car-following recordings (EU Joint Research Centre, CC BY 4.0).
 * Each file is one platoon: five cars on a test track, recorded with differential GPS at 10 Hz.
 * One {@link Pair} is a leader and the car directly behind it, kept only while that follower drove with ACC.
 */
public final class OpenAccData {

    /** One continuous stretch of a follower driving on ACC behind a leader. */
    public record Pair(String file, int position, String leader, String follower, String distanceSetting,
                       double[] time, double[] leadSpeed, double[] egoSpeed, double[] gap) {
        public int size() {
            return time.length;
        }
    }

    private static final int METADATA_ROWS = 5;
    private static final double MAX_SAMPLE_GAP_S = 0.15;

    private OpenAccData() {
    }

    public static List<Pair> load(Path folder) throws IOException {
        List<Pair> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("ASta_.*platoon\\d+\\.csv")).sorted().toList()) {
                out.addAll(read(f));
            }
        }
        return out;
    }

    private static List<Pair> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String[] order = lines.get(1).split(",");
        String acc = lines.get(3).split(",")[1].trim();
        String setting = lines.get(4).split(",")[1].trim();
        if (!"1".equals(acc)) {
            return List.of();
        }
        String[] header = lines.get(METADATA_ROWS).split(",");
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            col.put(header[i].trim(), i);
        }
        int vehicles = 5;
        List<Pair> out = new ArrayList<>();
        for (int k = 1; k < vehicles; k++) {
            List<double[]> run = new ArrayList<>();
            for (int r = METADATA_ROWS + 1; r < lines.size(); r++) {
                String[] c = lines.get(r).split(",", -1);
                double t = num(c, col.get("Time"));
                double lead = num(c, col.get("Speed" + k));
                double ego = num(c, col.get("Speed" + (k + 1)));
                double gap = num(c, col.get("IVS" + k));
                boolean onAcc = "ACC".equals(c[col.get("Driver" + (k + 1))].trim());
                boolean valid = onAcc && !Double.isNaN(t + lead + ego + gap) && gap > 0;
                boolean continuous = run.isEmpty() || t - run.get(run.size() - 1)[0] <= MAX_SAMPLE_GAP_S;
                if (!valid || !continuous) {
                    flush(out, run, file, order, k, setting);
                }
                if (valid) {
                    run.add(new double[] {t, lead, ego, gap});
                }
            }
            flush(out, run, file, order, k, setting);
        }
        return out;
    }

    private static void flush(List<Pair> out, List<double[]> run, Path file, String[] order, int k, String setting) {
        if (run.size() >= 100) {
            int n = run.size();
            double[] t = new double[n], lead = new double[n], ego = new double[n], gap = new double[n];
            for (int i = 0; i < n; i++) {
                double[] row = run.get(i);
                t[i] = row[0];
                lead[i] = row[1];
                ego[i] = row[2];
                gap[i] = row[3];
            }
            out.add(new Pair(file.getFileName().toString(), k - 1, order[k].trim(), order[k + 1].trim(), setting, t, lead, ego, gap));
        }
        run.clear();
    }

    private static double num(String[] c, Integer i) {
        if (i == null || i >= c.length || c[i].isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(c[i].trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
