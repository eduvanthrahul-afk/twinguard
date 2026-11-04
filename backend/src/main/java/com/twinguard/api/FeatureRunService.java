package com.twinguard.api;

import com.twinguard.feature.FeatureController;
import com.twinguard.feature.FeatureEngine;
import com.twinguard.feature.FeatureEngine.Cell;
import com.twinguard.feature.FeatureGate;
import com.twinguard.feature.FeatureReport;
import com.twinguard.feature.VehicleFeature;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Runs plugin feature tests in the background and streams their progress to the dashboard. */
@Service
public class FeatureRunService {

    public record Progress(int done, int budget, int failures, int coveredCells, int totalCells, List<Cell> cells) {
    }

    /** What to test, for one run. */
    public record Request(VehicleFeature feature, String targetId, String title, List<String> changed,
                          Supplier<FeatureController> baseline, Supplier<FeatureController> candidate, Set<String> declared, int budget) {
    }

    private static final int MAX_STORED_RUNS = 30;
    private static final int UPDATE_EVERY = 25;
    private static final long UPDATE_PAUSE_MS = 70;

    private static final class Run {
        final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        volatile Progress latest;
        volatile FeatureReport report;
        volatile String error;
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Map<String, Run> runs = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) {
            return size() > MAX_STORED_RUNS;
        }
    });

    public String start(Request request) {
        String id = UUID.randomUUID().toString();
        Run run = new Run();
        runs.put(id, run);
        executor.submit(() -> execute(run, request));
        return id;
    }

    public boolean exists(String id) {
        return runs.containsKey(id);
    }

    public FeatureReport report(String id) {
        Run run = runs.get(id);
        return run == null ? null : run.report;
    }

    public SseEmitter subscribe(String id) {
        Run run = runs.get(id);
        SseEmitter emitter = new SseEmitter(0L);
        run.emitters.add(emitter);
        emitter.onCompletion(() -> run.emitters.remove(emitter));
        emitter.onTimeout(() -> run.emitters.remove(emitter));
        emitter.onError(e -> run.emitters.remove(emitter));
        synchronized (run) {
            try {
                if (run.error != null) {
                    emitter.send(SseEmitter.event().name("failed").data(Map.of("message", run.error)));
                    emitter.complete();
                } else if (run.report != null) {
                    emitter.send(SseEmitter.event().name("complete").data(run.report));
                    emitter.complete();
                } else if (run.latest != null) {
                    emitter.send(SseEmitter.event().name("progress").data(run.latest));
                }
            } catch (IOException | IllegalStateException e) {
                run.emitters.remove(emitter);
            }
        }
        return emitter;
    }

    private void execute(Run run, Request r) {
        long[] pausedMs = {0};
        int[] failures = {0};
        int total = FeatureEngine.BINS * FeatureEngine.BINS;
        try {
            FeatureEngine.Listener listener = (e, done, coverage) -> {
                if (e.detected()) {
                    failures[0]++;
                }
                if (done % UPDATE_EVERY == 0 || done == r.budget()) {
                    List<Cell> cells = coverage.get();
                    Progress p = new Progress(done, r.budget(), failures[0], cells.size(), total, cells);
                    run.latest = p;
                    broadcast(run, "progress", p);
                    long pauseStart = System.nanoTime();
                    try {
                        Thread.sleep(UPDATE_PAUSE_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    pausedMs[0] += (System.nanoTime() - pauseStart) / 1_000_000;
                }
            };
            FeatureReport report = FeatureGate.run(r.feature(), r.targetId(), r.title(), r.changed(), r.baseline(), r.candidate(), r.declared(), r.budget(), System.nanoTime(), listener);
            FeatureReport honest = new FeatureReport(report.featureId(), report.targetId(), report.targetTitle(), report.verdict(), report.reason(), report.changedSettings(),
                    report.conventionalPassed(), report.conventionalTotal(), report.evaluations(), report.simulations(), Math.max(1, report.computeMs() - pausedMs[0]),
                    report.failingScenarios(), report.findingCounts(), report.coveredCells(), report.totalCells(), report.coverage(), report.counterexamples());
            synchronized (run) {
                run.report = honest;
                broadcast(run, "complete", honest);
                run.emitters.forEach(SseEmitter::complete);
            }
        } catch (RuntimeException e) {
            synchronized (run) {
                run.error = "The test run failed: " + e.getMessage();
                broadcast(run, "failed", Map.of("message", run.error));
                run.emitters.forEach(SseEmitter::complete);
            }
        }
    }

    private void broadcast(Run run, String event, Object data) {
        for (SseEmitter emitter : run.emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException e) {
                run.emitters.remove(emitter);
            }
        }
    }
}
