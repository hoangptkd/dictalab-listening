package com.dictalab.service;

import com.dictalab.config.ScoringProperties;
import org.springframework.stereotype.Service;

@Service
public class ScoringService {

    private final ScoringProperties properties;

    public ScoringService(ScoringProperties properties) {
        this.properties = properties;
    }

    public ScoreBreakdown calculate(int totalWords, int firstCorrect, int finalCorrect,
                                    int firstLetterHints, int wordReveals, int sentenceReveals,
                                    int totalReplays, int sentenceCount) {
        double firstAccuracy = percentage(firstCorrect, totalWords);
        double finalAccuracy = percentage(finalCorrect, totalWords);
        int extraReplays = Math.max(0, totalReplays - sentenceCount);
        // Version 2 normalizes opportunities, retaining the old 10-word/3-sentence reference scale.
        double penalty = (firstLetterHints * properties.firstLetterPenalty()
                + wordReveals * properties.wordRevealPenalty()) * 10.0 / Math.max(1, totalWords)
                + (sentenceReveals * properties.sentenceRevealPenalty()
                + extraReplays * properties.extraReplayPenalty()) * 3.0 / Math.max(1, sentenceCount);
        double independence = clamp(100.0 - penalty);
        double overall = totalWords == 0 ? 0 : firstAccuracy * properties.firstAttemptWeight()
                + finalAccuracy * properties.finalAccuracyWeight()
                + independence * properties.independenceWeight();
        return new ScoreBreakdown(round(firstAccuracy), round(finalAccuracy), round(clamp(overall)));
    }

    private double percentage(int correct, int total) {
        return total == 0 ? 0 : correct * 100.0 / total;
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public record ScoreBreakdown(double firstAttemptAccuracy, double finalAccuracy, double overallScore) {
    }
}
