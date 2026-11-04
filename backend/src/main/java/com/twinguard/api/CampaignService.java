package com.twinguard.api;

import com.twinguard.campaign.CampaignReport;
import com.twinguard.campaign.MutationCampaign;
import com.twinguard.feature.FeatureCampaign;
import com.twinguard.feature.Features;
import com.twinguard.feature.VehicleFeature;
import com.twinguard.mutation.CodeMutationCampaign;
import com.twinguard.mutation.CodeMutationReport;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Runs every campaign, at startup and on request, so the numbers are always measured. */
@Service
public class CampaignService {

    private static final double FAULT_SHARE = 0.2;
    private static final double MUTANT_SHARE = 0.2;

    public record Status(boolean running, String phase, double progress, String error, CampaignReport report,
                         CodeMutationReport codeMutation, Map<String, FeatureCampaign.Report> features) {
    }

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<String, FeatureCampaign.Report> features = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile String phase = "Not started";
    private volatile double progress;
    private volatile String error;
    private volatile CampaignReport report;
    private volatile CodeMutationReport codeMutation;

    @PostConstruct
    void runAtStartup() {
        start();
    }

    public synchronized boolean start() {
        if (running) {
            return false;
        }
        running = true;
        error = null;
        progress = 0;
        Thread worker = new Thread(this::execute, "campaigns");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public Status status() {
        return new Status(running, phase, progress, error, report, codeMutation, Map.copyOf(features));
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

    private void execute() {
        try {
            report = new MutationCampaign(MutationCampaign.DEFAULT_TRIALS, MutationCampaign.DEFAULT_BUDGET)
                    .run((name, value) -> onProgress(name, value * FAULT_SHARE));
            publish();
            codeMutation = new CodeMutationCampaign(CodeMutationCampaign.DEFAULT_TRIALS, CodeMutationCampaign.DEFAULT_BUDGET)
                    .run((name, value) -> onProgress(name, FAULT_SHARE + value * MUTANT_SHARE));
            publish();

            List<VehicleFeature> all = Features.all();
            double start = FAULT_SHARE + MUTANT_SHARE;
            double share = (1 - start) / all.size();
            for (int i = 0; i < all.size(); i++) {
                VehicleFeature feature = all.get(i);
                double from = start + i * share;
                features.put(feature.id(), new FeatureCampaign(feature).run((name, value) -> onProgress(name, from + value * share)));
                publish();
            }
            phase = "Done";
            progress = 1;
        } catch (RuntimeException e) {
            error = "The campaign failed: " + e.getMessage();
        } finally {
            running = false;
            publish();
        }
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
