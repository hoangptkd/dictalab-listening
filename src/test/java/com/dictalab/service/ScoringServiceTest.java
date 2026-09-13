package com.dictalab.service;

import com.dictalab.config.ScoringProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringServiceTest {

    private final ScoringService service = new ScoringService(new ScoringProperties(
            0.65, 0.25, 0.10, 2.0, 8.0, 20.0, 1.5
    ));

    @Test
    void givesFirstAttemptAccuracyTheLargestWeight() {
        ScoringService.ScoreBreakdown strongFirstAttempt = service.calculate(
                10, 9, 10, 0, 0, 0, 3, 3
        );
        ScoringService.ScoreBreakdown weakFirstAttempt = service.calculate(
                10, 4, 10, 0, 0, 0, 3, 3
        );

        assertThat(strongFirstAttempt.overallScore()).isEqualTo(93.5);
        assertThat(weakFirstAttempt.overallScore()).isEqualTo(61.0);
    }

    @Test
    void reducesScoreWhenHintsAndExtraReplaysAreUsed() {
        ScoringService.ScoreBreakdown independent = service.calculate(
                10, 8, 10, 0, 0, 0, 3, 3
        );
        ScoringService.ScoreBreakdown assisted = service.calculate(
                10, 8, 10, 2, 1, 1, 7, 3
        );

        assertThat(assisted.overallScore()).isLessThan(independent.overallScore());
    }
}
