package com.dictalab.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("dictalab.assessment")
public record AssessmentProperties(
        @Min(3) @Max(10) int minimumUnseenLessons,
        @Min(5) @Max(10) int maximumUnseenLessons
) {
    public AssessmentProperties {
        if (minimumUnseenLessons == 0) {
            minimumUnseenLessons = 5;
        }
        if (maximumUnseenLessons == 0) {
            maximumUnseenLessons = 10;
        }
        if (minimumUnseenLessons > maximumUnseenLessons) {
            throw new IllegalArgumentException("minimumUnseenLessons must not exceed maximumUnseenLessons");
        }
    }
}
