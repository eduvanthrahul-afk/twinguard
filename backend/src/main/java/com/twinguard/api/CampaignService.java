package com.twinguard.api;

import com.twinguard.campaign.CampaignReport;
import com.twinguard.campaign.MutationCampaign;
import com.twinguard.feature.FeatureCampaign;
import com.twinguard.feature.Features;
import com.twinguard.feature.VehicleFeature;
import com.twinguard.mutation.CodeMutationCampaign;
import com.twinguard.mutation.CodeMutationReport;
import com.twinguard.store.CampaignCache;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Runs every campaign, at startup and on request, so the numbers are always measured. Results are
 * saved, and reused at startup while the code that produced them hasn't changed.
 */
@Service
public class CampaignService {

    private static final double FAULT_SHARE = 0.2;
    private static final double MUTANT_SHARE = 0.2;

    /** @param measuredAt when the oldest of the shown results was measured; null until all are */
    public record Status(boolean running, String phase, double progress, String error, CampaignReport report,
                         CodeMutationReport codeMutation, Map<String, FeatureCampaign.Report> features, Instant measuredAt) {
    }

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<String, FeatureCampaign.Report> features = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile String phase = "Not started";
    private volatile double progress;
    private volatile String error;
    private volatile CampaignReport report;
    private volatile CodeMutationReport codeMutation;
    private volatile Instant measuredAt;
    private final CampaignCache cache;

    public CampaignService(CampaignCache cache) {
        this.cache = cache;
    }

    @PostConstruct
    void runAtStartup() {
        start(false);
    }

    /** Measures every score again, ignoring saved results. */
    public boolean start() {
        return start(true);
    }

    private synchronized boolean start(boolean remeasure) {
        if (running) {
            return false;
        }
        running = true;
        error = null;
        progress = 0;
        Thread worker = new Thread(() -> execute(remeasure), "campaigns");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public Status status() {
        return new Status(running, phase, progress, error, report, codeMutation, Map.copyOf(features), running ? null : measuredAt);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, "status", status());
        return emitter;
    }

    private void execute(boolean remeasure) {
        Instant[] oldest = {Instant.now()};
        try {
            report = measure("faults", CampaignReport.class, remeasure, oldest, () -> new MutationCampaign(MutationCampaign.DEFAULT_TRIALS, MutationCampaign.DEFAULT_BUDGET)
                    .run((name, value) -> onProgress(name, value * FAULT_SHARE)));
            publish();
            codeMutation = measure("code-mutants", CodeMutationReport.class, remeasure, oldest, () -> new CodeMutationCampaign(CodeMutationCampaign.DEFAULT_TRIALS, CodeMutationCampaign.DEFAULT_BUDGET)
                    .run((name, value) -> onProgress(name, FAULT_SHARE + value * MUTANT_SHARE)));
            publish();

            List<VehicleFeature> all = Features.all();
            double start = FAULT_SHARE + MUTANT_SHARE;
            double share = (1 - start) / all.size();
            for (int i = 0; i < all.size(); i++) {
                VehicleFeature feature = all.get(i);
                double from = start + i * share;
                features.put(feature.id(), measure("feature:" + feature.id(), FeatureCampaign.Report.class, remeasure, oldest,
                        () -> new FeatureCampaign(feature).run((name, value) -> onProgress(name, from + value * share))));
                publish();
            }
            measuredAt = oldest[0];
            phase = "Done";
            progress = 1;
        } catch (RuntimeException e) {
            error = "The campaign failed: " + e.getMessage();
        } finally {
            running = false;
            publish();
        }
    }

    /** Reuses a saved result for this exact code, unless asked to measure again, and saves anything newly measured. */
    private <T> T measure(String name, Class<T> type, boolean remeasure, Instant[] oldest, Supplier<T> run) {
        if (!remeasure) {
            var saved = cache.load(name, type);
            if (saved.isPresent()) {
                if (saved.get().measuredAt().isBefore(oldest[0])) {
                    oldest[0] = saved.get().measuredAt();
                }
                return saved.get().result();
            }
        }
        T result = run.get();
        cache.save(name, result);
        return result;
    }

    private void publish() {
        emitters.forEach(emitter -> send(emitter, "status", status()));
    }

    private synchronized void onProgress(String newPhase, double value) {
        phase = newPhase;
        progress = Math.max(progress, value);
        Map<String, Object> update = Map.of("phase", phase, "progress", progress);
        emitters.forEach(emitter -> send(emitter, "progress", update));
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }
}
