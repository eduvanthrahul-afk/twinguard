package com.twinguard.api;

import com.twinguard.gate.GateReport;
import com.twinguard.gate.ReleaseGate;
import com.twinguard.testgen.Coverage;
import com.twinguard.testgen.ScenarioSearch;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Runs gate tests in the background and streams their progress to the dashboard. */
@Service
public class RunService {

    public record Progress(int done, int budget, int failures, int coveredCells, int totalCells, double coveragePercent,
                           List<Coverage.Cell> cells) {
    }

    private static final int MAX_STORED_RUNS = 30;
    private static final int UPDATE_EVERY = 25;
    private static final long UPDATE_PAUSE_MS = 90;

    private static final class Run {
        final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        volatile Progress latest;
        volatile GateReport report;
        volatile String error;
        volatile ReleaseGate.Target target;
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Map<String, Run> runs = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) {
            return size() > MAX_STORED_RUNS;
        }
    });

    public String start(ReleaseGate.Target target, int budget, ScenarioSearch.Strategy strategy) {
        String id = UUID.randomUUID().toString();
        Run run = new Run();
        run.target = target;
        runs.put(id, run);
        executor.submit(() -> execute(run, target, budget, strategy));
        return id;
    }

    public GateReport report(String id) {
        Run run = runs.get(id);
        return run == null ? null : run.report;
    }

    public ReleaseGate.Target target(String id) {
        Run run = runs.get(id);
        return run == null ? null : run.target;
    }

    public boolean exists(String id) {
        return runs.containsKey(id);
    }

    public SseEmitter subscribe(String id) {
        Run run = runs.get(id);
        SseEmitter emitter = new SseEmitter(0L);
        run.emitters.add(emitter);
        emitter.onCompletion(() -> run.emitters.remove(emitter));
        emitter.onTimeout(() -> run.emitters.remove(emitter));
        emitter.onError(e -> run.emitters.remove(emitter));
        // A run may already have moved on, or finished, before the page connected.
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

    private void execute(Run run, ReleaseGate.Target target, int budget, ScenarioSearch.Strategy strategy) {
        long[] pausedMs = {0};
        int[] failures = {0};
        try {
            ScenarioSearch.Listener listener = (evaluation, done, coverage) -> {
                if (evaluation.detected()) {
                    failures[0]++;
                }
                if (done % UPDATE_EVERY == 0 || done == budget) {
                    Progress p = new Progress(done, budget, failures[0], coverage.coveredCells(), Coverage.TOTAL_CELLS,
                            coverage.coveredPercent(), coverage.snapshot());
                    run.latest = p;
                    broadcast(run, "progress", p);
                    // A short pause so the map visibly fills in. It is excluded from the reported compute time.
                    long pauseStart = System.nanoTime();
                    try {
                        Thread.sleep(UPDATE_PAUSE_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    pausedMs[0] += (System.nanoTime() - pauseStart) / 1_000_000;
                }
                return true;
            };
            GateReport report = ReleaseGate.run(target, budget, strategy, System.nanoTime(), listener);
            GateReport honest = report.withComputeMs(Math.max(1, report.computeMs() - pausedMs[0]));
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
