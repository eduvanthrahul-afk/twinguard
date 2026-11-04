package com.twinguard.cli;

import com.twinguard.gate.GateReport;
import org.springframework.web.util.HtmlUtils;

/** A self-contained evidence page that CI stores with each release decision. */
final class EvidenceHtml {

    private EvidenceHtml() {
    }

    static String render(GateReport r) {
        StringBuilder failures = new StringBuilder();
        for (GateReport.Counterexample c : r.counterexamples()) {
            failures.append(String.format(
                    "<tr><td>%s</td><td>%s</td><td>%.0f km/h, %.0f m, friction %.2f, noise %.2f m, load %.0f%%</td></tr>",
                    c.primary().type(), esc(c.primary().message()), c.scenario().speedKph(), c.scenario().distanceM(),
                    c.scenario().friction(), c.scenario().noiseM(), c.scenario().infotainmentLoad() * 100));
        }
        String failureTable = failures.length() == 0
                ? "<p>No confirmed failures in the scenarios tested. This is evidence, not proof of safety.</p>"
                : "<table><tr><th>Finding</th><th>What happened</th><th>Minimal scenario</th></tr>" + failures + "</table>";
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><title>TwinGuard Evidence</title>
                <style>
                body{font:15px/1.5 system-ui,sans-serif;max-width:860px;margin:40px auto;padding:0 16px;color:#17202a}
                h1{font-size:22px}.v{font-size:28px;font-weight:700}table{border-collapse:collapse;width:100%%}
                td,th{border:1px solid #d5dbe1;padding:8px;text-align:left;vertical-align:top}
                </style></head><body>
                <h1>TwinGuard release evidence</h1><p>%s</p><p class="v">%s</p><p>%s</p>
                <p>Legacy suite: %d of %d passed. Scenarios tested: %d. Coverage map: %.1f%% of %d cells. Compute time: %d ms.</p>
                <p>Changed settings: %s</p>
                <h2>Failures</h2>%s
                <h2>Limitations</h2>
                <p>Simulated evidence only. It does not replace hardware-in-the-loop or road testing. It does not cover
                camera or radar perception, real ECU timing, or the gap between simulation and real roads.</p>
                </body></html>
                """.formatted(esc(r.targetTitle()), r.verdict(), esc(r.reason()), r.legacy().passed(), r.legacy().total(),
                r.evaluations(), r.coveragePercent(), r.totalCells(), r.computeMs(),
                esc(String.join(", ", r.changedSettings())), failureTable);
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s);
    }
}
