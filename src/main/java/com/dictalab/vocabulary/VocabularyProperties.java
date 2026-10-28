package com.dictalab.vocabulary;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("dictalab.vocabulary")
public record VocabularyProperties(boolean enabled, @Min(1) @Max(100) int newCardsPerDay,
                                   @Min(1) @Max(50) int sessionSize) {
    public VocabularyProperties { if (newCardsPerDay == 0) newCardsPerDay=10; if (sessionSize == 0) sessionSize=20; }
}
