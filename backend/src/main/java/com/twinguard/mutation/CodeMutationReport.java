package com.twinguard.mutation;

import com.twinguard.campaign.CampaignReport.Score;
import com.twinguard.campaign.CampaignReport.SurvivorKind;
import java.util.List;

/** How the gate performed against bugs created automatically from the braking code. */
public record CodeMutationReport(
        int totalMutants,
        int trials,
        int budget,
        Score overall,
        Score randomBaseline,
        Score excludingNoBehaviourChange,
        int noBehaviourChangeMutants,
        int crashingMutants,
        List<OperatorScore> byOperator,
        List<MutantResult> mutants,
        long simulations,
        long computeMs) {

    public record OperatorScore(String operator, Score score) {
    }

    public record MutantResult(String id, String method, int line, String operator, String description, String sourceLine,
                               boolean detected, int detectedTrials, Double meanScenariosToDetect, boolean detectedByRandom,
                               String firstFinding, SurvivorKind survivorKind) {
    }
}
