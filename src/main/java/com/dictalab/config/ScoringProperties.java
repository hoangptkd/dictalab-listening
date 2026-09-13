package com.dictalab.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "dictalab.scoring")
public record ScoringProperties(
        @DecimalMin("0") @DecimalMax("1") double firstAttemptWeight,
        @DecimalMin("0") @DecimalMax("1") double finalAccuracyWeight,
        @DecimalMin("0") @DecimalMax("1") double independenceWeight,
        @PositiveOrZero double firstLetterPenalty,
        @PositiveOrZero double wordRevealPenalty,
        @PositiveOrZero double sentenceRevealPenalty,
        @PositiveOrZero double extraReplayPenalty
) {
}
